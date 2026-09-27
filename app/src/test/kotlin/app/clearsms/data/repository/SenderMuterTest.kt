package app.clearsms.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.prefs.SettingsRepositoryImpl
import app.clearsms.data.rules.BundledRuleLoader
import app.clearsms.data.rules.RuleEngine
import app.clearsms.domain.categorizer.ContactLookup
import app.clearsms.domain.categorizer.MessageCategorizer
import app.clearsms.domain.categorizer.SenderIdLookup
import app.clearsms.domain.model.Category
import app.clearsms.notification.MutedSenderGate
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
 * The unified mute/unmute entry point and what a mute does NOT touch.
 *
 * Storage and matching are the blocked-sender machinery reused - one
 * normalized set in the settings DataStore (so "VM-JIOPAY", "JIOPAY" and
 * "AD-JIOPAY-S" are one entry, and a phone number is keyed by its last ten
 * digits), backed up with the other preferences, and read back by the
 * same [SettingsRepositoryImpl] after a restart. A mute is ONLY a
 * notification decision: ingestion, categorisation, extraction, unread
 * state and the recycle bin behave exactly as for any other sender.
 *
 * Blocked + muted: muting a blocked sender is refused (already silent);
 * blocking a muted sender succeeds and clears the mute.
 */
@RunWith(RobolectricTestRunner::class)
class SenderMuterTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var repository: MessageRepositoryImpl
    private lateinit var settings: SettingsRepositoryImpl
    private lateinit var store: InMemoryPreferencesDataStore
    private lateinit var muter: SenderMuter
    private lateinit var blocker: SenderBlocker
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

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
        store = InMemoryPreferencesDataStore()
        settings = SettingsRepositoryImpl(store)
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
                recycleBinEnabled = { true },
            )
        muter = SenderMuter(settings)
        blocker = SenderBlocker(settings, repository, muter, InMemoryPreferencesDataStore(), scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    // region storage and normalisation - the blocked-sender machinery reused

    @Test
    fun `muting stores the normalized sender - the entry the Settings list shows`() =
        runBlocking<Unit> {
            assertThat(muter.mute("VM-JIOPAY-S")).isTrue()
            assertThat(settings.mutedSenders.first()).containsExactly("JIOPAY")
            // Route variants collapse onto the one entry.
            muter.mute("AD-JIOPAY")
            assertThat(settings.mutedSenders.first()).containsExactly("JIOPAY")
        }

    @Test
    fun `TRAI route variants and phone-number forms all match one mute`() =
        runBlocking {
            muter.mute("HDFCBK")
            muter.mute("+91 98765 43210")
            for (variant in listOf("HDFCBK", "VM-HDFCBK", "AD-HDFCBK-S", "hdfcbk")) {
                assertThat(muter.isMuted(variant)).isTrue()
            }
            for (number in listOf("9876543210", "+919876543210", "0091 98765 43210", "98765-43210")) {
                assertThat(muter.isMuted(number)).isTrue()
            }
            assertThat(muter.isMuted("HDFCLF")).isFalse()
            assertThat(muter.isMuted("9876543211")).isFalse()
            // The gate the router consults applies the identical rule.
            val gate = MutedSenderGate(settings)
            assertThat(gate.isMuted("VM-HDFCBK-S")).isTrue()
            assertThat(gate.allows("+91 98765 43210")).isFalse()
            assertThat(gate.allows("ICICIB")).isTrue()
        }

    @Test
    fun `unmute removes every stored variant, including a raw legacy or backup entry`() =
        runBlocking<Unit> {
            settings.setMutedSenders(setOf("VM-JIOPAY", "JIOPAY", "SPAMCO"))
            muter.unmute("jiopay")
            assertThat(settings.mutedSenders.first()).containsExactly("SPAMCO")
        }

    @Test
    fun `toggle flips the state and reports the new one`() =
        runBlocking {
            assertThat(muter.toggle("VM-JIOPAY")).isTrue()
            assertThat(muter.isMuted("JIOPAY")).isTrue()
            assertThat(muter.toggle("JIOPAY")).isFalse()
            assertThat(muter.isMuted("VM-JIOPAY")).isFalse()
        }

    @Test
    fun `a blank sender is never muted`() =
        runBlocking {
            assertThat(muter.mute("   ")).isFalse()
            assertThat(settings.mutedSenders.first()).isEmpty()
        }

    @Test
    fun `a mute survives a restart - the same store read by a fresh repository`() =
        runBlocking {
            muter.mute("VM-JIOPAY")
            // "Restart": new SettingsRepositoryImpl / SenderMuter / gate over
            // the SAME persisted preferences.
            val reopened = SettingsRepositoryImpl(store)
            assertThat(SenderMuter(reopened).isMuted("JIOPAY")).isTrue()
            assertThat(MutedSenderGate(reopened).isMuted("AD-JIOPAY")).isTrue()
        }

    // endregion

    // region blocked + muted

    @Test
    fun `muting a blocked sender is refused and stores nothing`() =
        runBlocking {
            blocker.block("VM-JIOPAY")
            assertThat(muter.mute("JIOPAY")).isFalse()
            assertThat(muter.toggle("AD-JIOPAY")).isNull()
            assertThat(settings.mutedSenders.first()).isEmpty()
        }

    @Test
    fun `blocking a muted sender succeeds and clears the mute`() =
        runBlocking {
            repository.ingestIncoming("VM-JIOPAY", "offer", 1_000L)
            muter.mute("JIOPAY")
            assertThat(muter.isMuted("JIOPAY")).isTrue()

            blocker.block("VM-JIOPAY")

            assertThat(settings.blockedSenders.first()).containsExactly("JIOPAY")
            assertThat(settings.mutedSenders.first()).isEmpty()
            // And the block did its usual work: the thread is binned.
            assertThat(repository.observeInbox(null, false).first()).isEmpty()
            assertThat(repository.observeBin().first()).hasSize(1)
        }

    // endregion

    // region a mute changes nothing but notifications

    @Test
    fun `a muted sender's messages still ingest, categorise, extract and count as unread`() =
        runBlocking {
            muter.mute("HDFCBK")
            muter.mute("BOOKMY")

            val txn =
                repository
                    .ingestIncoming(
                        "VM-HDFCBK",
                        "Sent Rs.250.00 From HDFC Bank A/C x1234 To SWIGGY On 12/07/26 Ref 519912345678 Not You? Call 18002586161",
                        1_000L,
                    ).entity
            val otp =
                repository
                    .ingestIncoming("AX-BOOKMY", "Your OTP is 4821 for the booking. Valid for 10 minutes.", 2_000L)
                    .entity

            // Not binned, not read, not flagged - an ordinary inbox row.
            for (entity in listOf(txn, otp)) {
                assertThat(entity.deletedAt).isNull()
                assertThat(entity.isRead).isFalse()
                assertThat(entity.isBlockedSender).isFalse()
            }
            // Categorised and extracted exactly as when unmuted.
            assertThat(otp.category).isEqualTo(Category.OTP)
            assertThat(otp.extractedOtp).isEqualTo("4821")
            assertThat(db.transactionDao().getAll()).hasSize(1)
            // Visible in the inbox and in the unread counts (badge).
            assertThat(repository.observeInbox(null, false).first().map { it.id }).containsExactly(txn.id, otp.id)
            assertThat(repository.observeUnreadCounts().first().sumOf { it.count }).isEqualTo(2)
            assertThat(repository.observeBin().first()).isEmpty()
        }

    // endregion
}
