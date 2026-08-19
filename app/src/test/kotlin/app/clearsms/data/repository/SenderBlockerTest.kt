package app.clearsms.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.prefs.SettingsRepositoryImpl
import app.clearsms.data.rules.BundledRuleLoader
import app.clearsms.data.rules.RuleEngine
import app.clearsms.domain.categorizer.ContactLookup
import app.clearsms.domain.categorizer.MessageCategorizer
import app.clearsms.domain.categorizer.SenderIdLookup
import app.clearsms.testing.InMemoryPreferencesDataStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The unified block/unblock entry point: one method updates the
 * authoritative settings set (what the Settings dialog lists), the derived
 * row flags, and bins the existing conversation - so a block made from the
 * inbox selection bar is finally visible in, and unblockable from, the
 * Settings dialog (the historical disconnect). Unblocking stops future
 * binning but never auto-restores. Legacy records (the old ui-prefs mirror
 * and flag-only rows) reconcile into the set at app start.
 */
@RunWith(RobolectricTestRunner::class)
class SenderBlockerTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var repository: MessageRepositoryImpl
    private lateinit var settings: SettingsRepositoryImpl
    private lateinit var uiStore: InMemoryPreferencesDataStore
    private lateinit var blocker: SenderBlocker
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private var binEnabled = true

    private object NoopStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        settings = SettingsRepositoryImpl(InMemoryPreferencesDataStore())
        uiStore = InMemoryPreferencesDataStore()
        repository =
            MessageRepositoryImpl(
                database = db,
                categorizer =
                    MessageCategorizer(
                        ruleEngine = RuleEngine(),
                        senderIdLookup = SenderIdLookup { null },
                        contactLookup = ContactLookup { false },
                    ),
                bundledRuleLoader = BundledRuleLoader(context, db.ruleDao(), json, NoopStore),
                json = json,
                blockedSenders = { settings.blockedSenders.first() },
                recycleBinEnabled = { binEnabled },
            )
        blocker = SenderBlocker(settings, repository, uiStore, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    @Test
    fun `blocking from the inbox path lists the sender in the settings set and unblock works`() =
        runBlocking {
            repository.ingestIncoming("VM-JIOPAY", "50% off recharge!", 1_000L)

            // The inbox selection-bar action passes the RAW sender.
            blocker.block("VM-JIOPAY")

            // Normalized entry - exactly what the Settings dialog lists.
            assertThat(settings.blockedSenders.first()).containsExactly("JIOPAY")

            // Unblocking from the dialog works against the same set.
            blocker.unblock("JIOPAY")
            assertThat(settings.blockedSenders.first()).isEmpty()
            assertThat(repository.ingestIncoming("VM-JIOPAY", "back again", 2_000L).entity.deletedAt).isNull()
        }

    @Test
    fun `blocking bins the existing conversation and flags its rows`() =
        runBlocking {
            val kept = repository.ingestIncoming("OKBANK", "legit", 500L).entity
            repository.ingestIncoming("VM-JIOPAY", "offer one", 1_000L)
            repository.ingestIncoming("JIOPAY", "offer two", 2_000L)

            blocker.block("VM-JIOPAY")

            // The blocked thread disappeared from the inbox; the other stayed.
            val inbox = repository.observeInbox(null, false).first()
            assertThat(inbox.map { it.id }).containsExactly(kept.id)
            val bin = repository.observeBin().first()
            assertThat(bin).hasSize(2)
            bin.forEach { assertThat(it.isBlockedSender).isTrue() }
        }

    @Test
    fun `blocking with the bin off drops the existing conversation outright`() =
        runBlocking<Unit> {
            binEnabled = false
            repository.ingestIncoming("VM-JIOPAY", "offer", 1_000L)

            blocker.block("VM-JIOPAY")

            assertThat(db.messageDao().getAll()).isEmpty()
            assertThat(settings.blockedSenders.first()).containsExactly("JIOPAY")
        }

    @Test
    fun `unblock removes raw legacy variants and does not restore binned messages`() =
        runBlocking {
            // A raw entry, as reconcile or an old backup could leave behind.
            settings.setBlockedSenders(setOf("VM-JIOPAY"))
            repository.ingestIncoming("JIOPAY", "binned while blocked", 1_000L)
            assertThat(repository.observeBin().first()).hasSize(1)

            blocker.unblock("JIOPAY")

            assertThat(settings.blockedSenders.first()).isEmpty()
            // Deliberately no auto-restore: the bin keeps the message until
            // the user restores it there.
            assertThat(repository.observeBin().first()).hasSize(1)
            assertThat(repository.observeInbox(null, false).first()).isEmpty()
        }

    @Test
    fun `the block survives deleting every message of the sender`() =
        runBlocking {
            repository.ingestIncoming("VM-JIOPAY", "offer", 1_000L)
            blocker.block("VM-JIOPAY")
            repository.deleteForever(repository.binMessageIds())
            assertThat(db.messageDao().getAll()).isEmpty()

            val next = repository.ingestIncoming("JIOPAY", "still blocked", 2_000L).entity

            assertThat(next.deletedAt).isNotNull()
            assertThat(next.isBlockedSender).isTrue()
        }

    @Test
    fun `app-start reconcile folds the legacy ui-prefs mirror and row flags into the set`() =
        runBlocking<Unit> {
            // Legacy 1: the old Settings-dialog mirror in the ui_settings store.
            val legacyKey = stringSetPreferencesKey("blocked_senders")
            uiStore.edit { it[legacyKey] = setOf("VM-JIOPAY") }
            // Legacy 2: a flag-only block, as the old inbox action left it.
            repository.ingestIncoming("BX-SPAMCO", "hello", 1_000L)
            repository.setBlocked("SPAMCO", blocked = true)

            blocker.reconcileLegacy()

            assertThat(settings.blockedSenders.first()).containsExactly("JIOPAY", "SPAMCO")
            // The drained mirror never re-adds after a later unblock.
            assertThat(uiStore.data.first()[legacyKey]).isNull()
            blocker.unblock("JIOPAY")
            blocker.reconcileLegacy()
            assertThat(settings.blockedSenders.first()).containsExactly("SPAMCO")
        }
}
