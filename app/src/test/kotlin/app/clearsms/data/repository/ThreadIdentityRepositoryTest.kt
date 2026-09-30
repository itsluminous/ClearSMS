package app.clearsms.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.backup.BackupDocument
import app.clearsms.data.backup.BackupManager
import app.clearsms.data.backup.MessageBackup
import app.clearsms.data.backup.PinBackup
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.rules.BundledRuleLoader
import app.clearsms.data.rules.RuleAction
import app.clearsms.data.rules.RuleDefinition
import app.clearsms.data.rules.RuleEngine
import app.clearsms.data.rules.RuleMatch
import app.clearsms.data.rules.RuleSources
import app.clearsms.data.rules.toEntity
import app.clearsms.domain.categorizer.ContactLookup
import app.clearsms.domain.categorizer.MessageCategorizer
import app.clearsms.domain.categorizer.SenderIdLookup
import app.clearsms.domain.model.Category
import app.clearsms.domain.rules.RuleApplyScope
import app.clearsms.domain.rules.RuleScopeResolver
import app.clearsms.domain.rules.SenderRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream

/**
 * Thread identity through the repository (issue #42): the import and live
 * paths use the provider thread when there is one and the sender key when
 * there is not; rule-based categorisation - the app's main power - is not
 * touched by either, including for sender rules users created under the
 * OLD key. Every number is synthetic.
 */
@RunWith(RobolectricTestRunner::class)
class ThreadIdentityRepositoryTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var repository: MessageRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true }

    private object NoopStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }

    @Before
    fun setUp() {
        SenderNormalizer.defaultRegion = "PL"
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
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
            )
    }

    @After
    fun tearDown() {
        db.close()
        SenderNormalizer.defaultRegion = null
    }

    private suspend fun importRow(
        id: Long,
        sender: String,
        body: String,
        providerThreadId: Long? = null,
    ) = ImportedSmsRow(
        systemSmsId = id,
        sender = sender,
        body = body,
        timestampMs = id * 1_000,
        isRead = true,
        enriched = repository.classify(repository.rulesSnapshot(), sender, body, id * 1_000),
        providerThreadId = providerThreadId,
    )

    // region provider thread vs fallback

    @Test
    fun `import - the provider thread id is used when present, across the page boundary too`() =
        runBlocking {
            // Unknown region so the sender key alone would NOT merge these.
            SenderNormalizer.defaultRegion = null
            repository.persistImportedPage(listOf(importRow(1, "+33612345678", "a", providerThreadId = 9)))
            repository.persistImportedPage(
                listOf(
                    importRow(2, "0612345678", "b", providerThreadId = 9),
                    importRow(3, "0612345678", "c", providerThreadId = 9),
                ),
            )

            val rows = db.messageDao().getAll().sortedBy { it.id }
            assertThat(rows.map { it.threadId }.toSet()).hasSize(1)
            assertThat(rows.map { it.providerThreadId }).containsExactly(9L, 9L, 9L)
            // Keys stay honest per row.
            assertThat(rows.map { it.normalizedSender }).containsExactly("612345678", "0612345678", "0612345678").inOrder()
        }

    @Test
    fun `import - the sender key is the fallback when the provider gives no thread`() =
        runBlocking {
            repository.persistImportedPage(
                listOf(
                    importRow(1, "+48601234567", "a"),
                    importRow(2, "601234567", "b"),
                    importRow(3, "+48 601 234 567", "c"),
                ),
            )

            val rows = db.messageDao().getAll()
            assertThat(rows.map { it.threadId }.toSet()).hasSize(1)
            assertThat(rows.map { it.normalizedSender }.toSet()).containsExactly("601234567")
            assertThat(rows.map { it.providerThreadId }).containsExactly(null, null, null).inOrder()
        }

    @Test
    fun `import - a group provider thread of two people never merges them`() =
        runBlocking {
            repository.persistImportedPage(
                listOf(
                    importRow(1, "+48601234567", "a", providerThreadId = 77),
                    importRow(2, "+48509876543", "b", providerThreadId = 77),
                    importRow(3, "601234567", "c", providerThreadId = 77),
                ),
            )

            val rows = db.messageDao().getAll().sortedBy { it.id }
            assertThat(rows[0].threadId).isEqualTo(rows[2].threadId)
            assertThat(rows[1].threadId).isNotEqualTo(rows[0].threadId)
        }

    @Test
    fun `import - short codes and alphanumeric ids never anchor and never merge with a number`() =
        runBlocking {
            SenderNormalizer.defaultRegion = "IN"
            repository.persistImportedPage(
                listOf(
                    importRow(1, "56767", "code", providerThreadId = 40),
                    importRow(2, "+919876556767", "number", providerThreadId = 40),
                    importRow(3, "VM-HDFCBK-S", "bank", providerThreadId = 41),
                    importRow(4, "AD-HDFCBK", "bank", providerThreadId = 42),
                ),
            )

            val rows = db.messageDao().getAll().sortedBy { it.id }
            assertThat(rows[0].threadId).isNotEqualTo(rows[1].threadId)
            assertThat(rows[2].threadId).isEqualTo(rows[3].threadId)
            assertThat(rows.map { it.providerThreadId }).containsExactly(null, 40L, null, null).inOrder()
        }

    @Test
    fun `live - an incoming message joins the imported thread through the provider thread, or its key`() =
        runBlocking {
            SenderNormalizer.defaultRegion = null
            repository.persistImportedPage(listOf(importRow(1, "+33612345678", "a", providerThreadId = 9)))
            val imported = db.messageDao().getAll().single()

            val viaAnchor = repository.ingestIncoming("0612345678", "b", 5_000, systemSmsId = 50, providerThreadId = 9)
            val viaKey = repository.ingestIncoming("+33 6 12 34 56 78", "c", 6_000, systemSmsId = 51, providerThreadId = null)
            val stranger = repository.ingestIncoming("0698765432", "d", 7_000, systemSmsId = 52, providerThreadId = null)

            assertThat(viaAnchor.entity.threadId).isEqualTo(imported.threadId)
            assertThat(viaKey.entity.threadId).isEqualTo(imported.threadId)
            assertThat(stranger.entity.threadId).isNotEqualTo(imported.threadId)
        }

    @Test
    fun `live - a deleted provider row (no thread id) still lands in the right thread by key`() =
        runBlocking {
            val first = repository.ingestIncoming("+48601234567", "a", 1_000, systemSmsId = 1, providerThreadId = 3)
            val second = repository.ingestIncoming("601 234 567", "b", 2_000, systemSmsId = null, providerThreadId = null)

            assertThat(second.entity.threadId).isEqualTo(first.entity.threadId)
            assertThat(second.entity.providerThreadId).isNull()
        }

    // endregion

    // region rules are not impacted

    /** A sender rule as the app stored it BEFORE #42: pattern from the old ten-digit core. */
    private fun oldSchemeRule(oldCore: String) =
        RuleDefinition(
            id = "user_old_$oldCore",
            name = "Always spam: $oldCore",
            priority = SenderRule.USER_BAND_PRIORITY,
            match = RuleMatch(senderPattern = "(?i)$oldCore"),
            action = RuleAction(category = "spam"),
        )

    @Test
    fun `a sender rule created under the OLD key still matches its sender after the key moved`() =
        runBlocking {
            // Blocking "+48 601 234 567" as spam before #42 produced (?i)8601234567.
            val rule = oldSchemeRule("8601234567")
            db.ruleDao().insert(rule.toEntity(json, RuleSources.USER))

            val entity = repository.insertIncoming("+48601234567", "Kup teraz! Promocja", 1_000)

            // Matched at ingestion, under the NEW thread key.
            assertThat(entity.category).isEqualTo(Category.SPAM)
            assertThat(entity.normalizedSender).isEqualTo("601234567")
            // And the engine alone agrees: matching runs on the raw sender.
            val engine = RuleEngine()
            assertThat(engine.evaluate(listOf(rule), "+48601234567", "x")?.matchedRuleId).isEqualTo(rule.id)
            assertThat(engine.evaluate(listOf(rule), "+48601234568", "x")).isNull()
        }

    @Test
    fun `re-saving an OLD-key sender rule still re-sorts that sender's existing messages`() =
        runBlocking {
            val a = repository.insertIncoming("+48601234567", "first", 1_000)
            val b = repository.insertIncoming("+48601234567", "second", 2_000)
            val other = repository.insertIncoming("+48509876543", "third", 3_000)
            assertThat(a.category).isNotEqualTo(Category.SPAM)

            val rule = oldSchemeRule("8601234567")
            db.ruleDao().insert(rule.toEntity(json, RuleSources.USER))
            val scope =
                RuleScopeResolver.resolve(
                    senderPattern = rule.match.senderPattern!!,
                    sourceSender = "+48601234567",
                    boundToSender = true,
                    senderPatternEdited = false,
                )
            assertThat(scope).isInstanceOf(RuleApplyScope.Sender::class.java)
            val processed = repository.recategorizeSenderCore((scope as RuleApplyScope.Sender).senderCore)

            assertThat(processed).isEqualTo(2)
            assertThat(db.messageDao().getById(a.id)!!.category).isEqualTo(Category.SPAM)
            assertThat(db.messageDao().getById(b.id)!!.category).isEqualTo(Category.SPAM)
            assertThat(db.messageDao().getById(other.id)!!.category).isNotEqualTo(Category.SPAM)
        }

    @Test
    fun `a NEW one-step sender rule matches every dialling variant, including the one the old key missed`() =
        runBlocking {
            val rule = SenderRule.definition("+48 601 234 567", "spam", id = "user_new")
            assertThat(rule.match.senderPattern).isEqualTo("(?i)601234567")
            db.ruleDao().insert(rule.toEntity(json, RuleSources.USER))

            val international = repository.insertIncoming("+48601234567", "a", 1_000)
            val local = repository.insertIncoming("601234567", "b", 2_000)
            val other = repository.insertIncoming("+48509876543", "c", 3_000)

            assertThat(international.category).isEqualTo(Category.SPAM)
            assertThat(local.category).isEqualTo(Category.SPAM)
            assertThat(other.category).isNotEqualTo(Category.SPAM)
            assertThat(international.threadId).isEqualTo(local.threadId)
            // Immediate re-sort scope finds both variants.
            val processed = repository.recategorizeSenderCore(SenderRule.senderCore("+48 601 234 567"))
            assertThat(processed).isEqualTo(2)
        }

    @Test
    fun `body-only rules are unaffected by thread identity`() =
        runBlocking {
            val rule =
                RuleDefinition(
                    id = "user_body",
                    name = "body",
                    priority = SenderRule.USER_BAND_PRIORITY,
                    match = RuleMatch(bodyPattern = "(?i)promocja"),
                    action = RuleAction(category = "promotional"),
                )
            db.ruleDao().insert(rule.toEntity(json, RuleSources.USER))

            val a = repository.insertIncoming("+48601234567", "Promocja dnia", 1_000)
            val b = repository.insertIncoming("VM-SHOP", "promocja", 2_000)
            val c = repository.insertIncoming("56767", "nothing here", 3_000)

            assertThat(a.category).isEqualTo(Category.PROMOTIONAL)
            assertThat(b.category).isEqualTo(Category.PROMOTIONAL)
            assertThat(c.category).isNotEqualTo(Category.PROMOTIONAL)
        }

    // endregion

    @Test
    fun `restoring a backup written under the OLD key does not bring the split threads back`() =
        runBlocking {
            val document =
                BackupDocument(
                    createdAt = 0,
                    messages =
                        listOf(
                            MessageBackup(1, 1, "+48601234567", "8601234567", "a", 1_000, true, false, "PERSONAL"),
                            MessageBackup(2, 2, "601234567", "601234567", "b", 2_000, false, false, "PERSONAL"),
                            MessageBackup(3, 3, "VM-HDFCBK", "HDFCBK", "c", 3_000, true, false, "PROMOTIONAL"),
                        ),
                    pins = listOf(PinBackup("8601234567", 5)),
                )
            val bytes = json.encodeToString(BackupDocument.serializer(), document).toByteArray()

            BackupManager(db, json).importFrom(ByteArrayInputStream(bytes))

            val rows = db.messageDao().getAll().sortedBy { it.id }
            assertThat(rows.map { it.threadId }).containsExactly(1L, 1L, 3L).inOrder()
            assertThat(rows.map { it.normalizedSender }).containsExactly("601234567", "601234567", "HDFCBK").inOrder()
            assertThat(rows.map { it.isRead }).containsExactly(true, false, true).inOrder()
            assertThat(db.threadPinDao().getAll().map { it.normalizedSender }).containsExactly("601234567").inOrder()
        }
}
