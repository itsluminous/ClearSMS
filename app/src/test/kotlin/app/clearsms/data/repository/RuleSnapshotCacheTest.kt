package app.clearsms.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.backup.BackupManager
import app.clearsms.data.backup.RoomUserRuleStore
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.rules.BundledRuleLoader
import app.clearsms.data.rules.RuleDocument
import app.clearsms.data.rules.RuleEngine
import app.clearsms.data.rules.RuleExporter
import app.clearsms.data.rules.RuleImporter
import app.clearsms.data.rules.RuleSources
import app.clearsms.data.rules.RulesRevision
import app.clearsms.data.rules.toEntity
import app.clearsms.domain.categorizer.ContactLookup
import app.clearsms.domain.categorizer.MessageCategorizer
import app.clearsms.domain.categorizer.SenderIdLookup
import app.clearsms.domain.model.Category
import app.clearsms.domain.rules.SenderRule
import app.clearsms.testing.InMemoryPreferencesDataStore
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The classifier's cached rule snapshot (issue #153) and, above all, its
 * invalidation: a rule the user saves must decide the very next message. A
 * stale snapshot would be a worse bug than the per-message decode it saves,
 * so every writer of the rules table is pinned here to bump the shared
 * [RulesRevision], and the end-to-end path (save a rule, receive a message)
 * is proven against the real repository.
 */
@RunWith(RobolectricTestRunner::class)
class RuleSnapshotCacheTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var loader: BundledRuleLoader
    private lateinit var repository: MessageRepositoryImpl
    private lateinit var rules: RuleRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true }
    private val revision = RulesRevision()

    private val sender = "AX-NEWBRD"
    private val body = "Hello from a brand new sender nobody has a rule for yet."

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        loader = BundledRuleLoader(context, db.ruleDao(), json, InMemoryPreferencesDataStore(), rulesRevision = revision)
        repository =
            MessageRepositoryImpl(
                database = db,
                categorizer =
                    MessageCategorizer(
                        ruleEngine = RuleEngine(),
                        senderIdLookup = SenderIdLookup { null },
                        contactLookup = ContactLookup { false },
                    ),
                bundledRuleLoader = loader,
                json = json,
            )
        rules = RuleRepositoryImpl(db.ruleDao(), loader, RuleImporter(json), RuleExporter(json), json)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `the decoded snapshot is reused across messages until the rules change`() =
        runBlocking {
            val first = repository.rulesSnapshot()
            val second = repository.rulesSnapshot()
            // Same instance: no re-query, no re-decode on the second message.
            assertThat(second).isSameInstanceAs(first)

            revision.bump()

            val third = repository.rulesSnapshot()
            assertThat(third).isNotSameInstanceAs(first)
        }

    @Test
    fun `a sender rule saved from the UI classifies the very next incoming message`() =
        runBlocking {
            val before = repository.insertIncoming(sender, body, 1_000L)
            assertThat(before.category).isNotEqualTo(Category.SPAM)

            // The user's "Always sort as Spam" for this sender, through the
            // same repository the rule editor uses.
            rules.addUserRule(SenderRule.definition(sender, "spam", id = "user_spam_newbrd"))

            val after = repository.insertIncoming(sender, body, 2_000L)
            assertThat(after.category).isEqualTo(Category.SPAM)
        }

    @Test
    fun `disabling, deleting and re-adding the rule each take effect on the next message`() =
        runBlocking {
            val rule = SenderRule.definition(sender, "spam", id = "user_spam_toggle")
            rules.addUserRule(rule)
            assertThat(repository.insertIncoming(sender, body, 1_000L).category).isEqualTo(Category.SPAM)

            rules.setRuleEnabled(rule.id, false)
            assertThat(repository.insertIncoming(sender, body, 2_000L).category).isNotEqualTo(Category.SPAM)

            rules.setRuleEnabled(rule.id, true)
            assertThat(repository.insertIncoming(sender, body, 3_000L).category).isEqualTo(Category.SPAM)

            rules.deleteRule(rule.id)
            assertThat(repository.insertIncoming(sender, body, 4_000L).category).isNotEqualTo(Category.SPAM)
        }

    @Test
    fun `every writer of the rules table bumps the shared revision`() =
        runBlocking {
            val userRow = SenderRule.definition(sender, "spam", id = "user_bump").toEntity(json, RuleSources.USER)
            val writers: List<Pair<String, suspend () -> Unit>> =
                listOf(
                    "addUserRule" to { rules.addUserRule(SenderRule.definition(sender, "spam", id = "user_add")) },
                    "setRuleEnabled" to { rules.setRuleEnabled("user_add", false) },
                    "deleteRule" to { rules.deleteRule("user_add") },
                    "importRules" to {
                        rules.importRules(RuleExporter(json).export(listOf(userRow)))
                    },
                    "restoreParkedRule" to {
                        rules.restoreParkedRule(SenderRule.definition(sender, "spam", id = "user_parked"), RuleSources.USER, enabled = true)
                    },
                    "RoomUserRuleStore.upsertUserRules (settings restore)" to {
                        RoomUserRuleStore(db, revision).upsertUserRules(listOf(userRow.copy(id = "user_settings_restore")))
                    },
                    "BackupManager.importFrom" to {
                        val backup = BackupManager(db, json, revision)
                        val bytes = ByteArrayOutputStream().also { backup.exportTo(it) }.toByteArray()
                        backup.importFrom(ByteArrayInputStream(bytes))
                    },
                    "BundledRuleLoader.reseed" to {
                        loader.reseed(RuleDocument(version = "test", rules = emptyList()))
                    },
                )
            for ((name, write) in writers) {
                val before = revision.current
                write()
                assertWithMessage("revision after $name").that(revision.current).isGreaterThan(before)
            }
        }

    @Test
    fun `a writer that bypasses every repository is still caught by the table observer`() =
        runBlocking {
            // The safety net: a raw DAO write nobody told the revision about.
            // Room's invalidation tracker notices the commit shortly after
            // and bumps the revision - later than an explicit bump, which is
            // why every known writer bumps synchronously, but never never.
            repository.insertIncoming(sender, body, 1_000L)
            val before = revision.current
            db.ruleDao().insert(SenderRule.definition(sender, "spam", id = "user_raw_dao").toEntity(json, RuleSources.USER))
            withTimeout(5_000) {
                while (revision.current == before) delay(10)
            }
            assertThat(repository.insertIncoming(sender, body, 2_000L).category).isEqualTo(Category.SPAM)
        }

    @Test
    fun `a backup restore replaces the rules that classify the next message`() =
        runBlocking {
            // Backup taken WITH the spam rule...
            rules.addUserRule(SenderRule.definition(sender, "spam", id = "user_backup_spam"))
            val backup = BackupManager(db, json, revision)
            val bytes = ByteArrayOutputStream().also { backup.exportTo(it) }.toByteArray()
            // ...then the rule is deleted, and messages flow without it.
            rules.deleteRule("user_backup_spam")
            assertThat(repository.insertIncoming(sender, body, 1_000L).category).isNotEqualTo(Category.SPAM)

            backup.importFrom(ByteArrayInputStream(bytes))

            // Restored under the namespaced user id, as BackupManager stores it.
            assertThat(db.ruleDao().getBySource(RuleSources.USER)).hasSize(1)
            assertThat(repository.insertIncoming(sender, body, 2_000L).category).isEqualTo(Category.SPAM)
        }

    @Test
    fun `the bundled asset is read once per process and the marker still decides reseeds`() =
        runBlocking {
            loader.ensureLoaded()
            val builtins = db.ruleDao().getBySource(RuleSources.BUILTIN)
            assertThat(builtins).isNotEmpty()
            val revisionAfterSeed = revision.current

            // Steady state: no reseed, no revision change, builtin rows untouched.
            repeat(3) { loader.ensureLoaded() }
            assertThat(revision.current).isEqualTo(revisionAfterSeed)
            assertThat(db.ruleDao().getBySource(RuleSources.BUILTIN)).isEqualTo(builtins)

            // A builtin row removed behind the loader's back is NOT restored
            // by the memo alone - the marker still says the table is current,
            // exactly as before the memo existed...
            db.ruleDao().deleteById(builtins.first().id)
            loader.ensureLoaded()
            assertThat(db.ruleDao().getById(builtins.first().id)).isNull()

            // ...and a stale marker (what an app update leaves behind) still
            // reseeds, through the memo, and bumps the revision.
            val stale = InMemoryPreferencesDataStore()
            val updated =
                BundledRuleLoader(ApplicationProvider.getApplicationContext(), db.ruleDao(), json, stale, rulesRevision = revision)
            updated.ensureLoaded()
            assertThat(db.ruleDao().getById(builtins.first().id)).isNotNull()
            assertThat(revision.current).isGreaterThan(revisionAfterSeed)
        }
}
