package app.clearsms.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.Converters
import app.clearsms.data.rules.BundledRuleLoader
import app.clearsms.data.rules.RuleEngine
import app.clearsms.data.rules.RuleExporter
import app.clearsms.data.rules.RuleImporter
import app.clearsms.data.rules.RuleSources
import app.clearsms.data.rules.toDefinition
import app.clearsms.domain.categorizer.ContactLookup
import app.clearsms.domain.categorizer.MessageCategorizer
import app.clearsms.domain.categorizer.SenderIdLookup
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SubCategory
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
import java.io.File

/**
 * A user rule "always sort this sender as Spam", end to end: it re-sorts the
 * sender's existing messages at once, files that sender's NEW messages as
 * SPAM through ingestion, yet an OTP or a debit from the same sender still
 * arrives as OTP / an IMPORTANT transaction (with its finance row). The rule
 * survives export → import and a bundled-rules reseed. The category is
 * persisted by NAME through the Room type converter, so no schema change or
 * migration is involved and existing rows keep their category verbatim.
 * Fixtures are synthetic.
 */
@RunWith(RobolectricTestRunner::class)
class SpamRuleEndToEndTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var repository: MessageRepositoryImpl
    private lateinit var rules: RuleRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true }

    /** Never remembers a loaded version, so every ensureLoaded() is a full reseed. */
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
        val loader = BundledRuleLoader(context, db.ruleDao(), json, NoopStore)
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
    fun tearDown() = db.close()

    private val promo = "Mega sale this weekend! Flat 70% off on everything at JUNKCO. Visit our store now."

    @Test
    fun `the sender rule re-sorts existing messages and files new ones as spam`() =
        runBlocking {
            val before = repository.insertIncoming("VM-JUNKCO-S", promo, 1_000L)
            assertThat(before.category).isNotEqualTo(Category.SPAM)

            rules.addUserRule(SenderRule.definition("VM-JUNKCO-S", "spam", id = "user_spam"))
            assertThat(repository.recategorizeSenderCore(SenderRule.senderCore("VM-JUNKCO-S"))).isEqualTo(1)
            assertThat(db.messageDao().getById(before.id)!!.category).isEqualTo(Category.SPAM)

            val after = repository.insertIncoming("AD-JUNKCO", "$promo (again)", 2_000L)
            assertThat(after.category).isEqualTo(Category.SPAM)
            assertThat(after.subCategory).isNotEqualTo(SubCategory.SCAM)
        }

    @Test
    fun `an OTP from the spam-ruled sender still arrives as OTP with its code`() =
        runBlocking {
            rules.addUserRule(SenderRule.definition("VM-JUNKCO-S", "spam", id = "user_spam"))
            val otp = repository.insertIncoming("VM-JUNKCO-S", "482913 is your OTP for login. Valid for 10 minutes.", 1_000L)
            assertThat(otp.category).isEqualTo(Category.OTP)
            assertThat(otp.extractedOtp).isEqualTo("482913")
        }

    @Test
    fun `a debit from the spam-ruled sender is an important transaction with a finance row`() =
        runBlocking {
            rules.addUserRule(SenderRule.definition("VM-JUNKCO-S", "spam", id = "user_spam"))
            val debit =
                repository.insertIncoming(
                    "VM-JUNKCO-S",
                    "Rs.2,500.00 debited from A/c XX1234 on 12-07-26 to VPA shop@upi. Avl Bal Rs.10,000.00",
                    1_000L,
                )
            assertThat(debit.category).isEqualTo(Category.IMPORTANT)
            assertThat(debit.subCategory).isEqualTo(SubCategory.TRANSACTION)
            assertThat(db.transactionDao().getAll().map { it.rawSmsId }).contains(debit.id)
        }

    @Test
    fun `a spam rule round-trips through export and import`() =
        runBlocking {
            rules.addUserRule(SenderRule.definition("VM-JUNKCO-S", "spam", id = "user_spam"))
            val exported = rules.exportUserRules()
            assertThat(exported).contains("\"category\":\"spam\"")

            db.ruleDao().deleteAll()
            rules.importRules(exported)

            val imported =
                db
                    .ruleDao()
                    .getBySource(RuleSources.USER)
                    .single()
                    .toDefinition(json)!!
            assertThat(imported.action.category).isEqualTo("spam")
            assertThat(RuleEngine.categoryOf(imported.action.category)).isEqualTo(Category.SPAM)
            assertThat(repository.insertIncoming("VM-JUNKCO-S", promo, 1_000L).category).isEqualTo(Category.SPAM)
        }

    @Test
    fun `a bundled-rules reseed does not delete the user's spam rule`() =
        runBlocking {
            rules.addUserRule(SenderRule.definition("VM-JUNKCO-S", "spam", id = "user_spam"))
            rules.ensureBundledRulesLoaded()
            rules.ensureBundledRulesLoaded()

            val user = db.ruleDao().getBySource(RuleSources.USER)
            assertThat(user.map { it.id }).containsExactly("user_spam")
            assertThat(db.ruleDao().getBySource(RuleSources.BUILTIN)).isNotEmpty()
            // And the bundled bait rules now land in Spam, flagged.
            val bait =
                repository.insertIncoming(
                    "BP-RANDOM-S",
                    "Congratulations! You have won a lottery prize. Claim now https://tinyurl.com/win-big",
                    3_000L,
                )
            assertThat(bait.category).isEqualTo(Category.SPAM)
            assertThat(bait.subCategory).isEqualTo(SubCategory.SCAM)
        }

    @Test
    fun `category is stored by name - SPAM needs no schema change and old rows are untouched`() {
        val converters = Converters()
        for (category in Category.entries) {
            assertThat(converters.toCategory(converters.fromCategory(category))).isEqualTo(category)
        }
        assertThat(converters.fromCategory(Category.SPAM)).isEqualTo("SPAM")
        // The committed schema for the current database version declares the
        // column as TEXT with no CHECK constraint, so adding an enum entry is
        // not a schema change: the version stays put and no migration exists
        // for it. (Existing rows are only ever re-filed by an explicit
        // re-sort, never by the upgrade itself.)
        val schemaDir = File("schemas/app.clearsms.data.db.ClearSmsDatabase")
        val latest = schemaDir.listFiles()!!.maxBy { it.nameWithoutExtension.toInt() }
        val schema = latest.readText()
        assertThat(
            schema,
        ).contains("\"fieldPath\": \"category\",\n            \"columnName\": \"category\",\n            \"affinity\": \"TEXT\"")
        assertThat(schema).doesNotContain("CHECK")
    }
}
