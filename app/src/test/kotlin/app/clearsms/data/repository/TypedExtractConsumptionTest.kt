package app.clearsms.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.rules.BundledRuleLoader
import app.clearsms.data.rules.RuleEngine
import app.clearsms.data.rules.RuleSources
import app.clearsms.data.rules.toEntity
import app.clearsms.domain.categorizer.ContactLookup
import app.clearsms.domain.categorizer.MessageCategorizer
import app.clearsms.domain.categorizer.SenderIdLookup
import app.clearsms.domain.model.TransactionType
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
import java.time.LocalDate
import java.time.ZoneId

/**
 * The repository consumes the engine's TYPED extracts directly — amounts,
 * due dates and the debit/credit direction arrive parsed, and the repository
 * no longer re-parses the raw capture strings (its `toAmount` /
 * `toTransactionType` / date-reparse helpers are gone). These tests drive
 * the full rule → typed extract → derived-row path over user rules with
 * every consuming shape: a transaction built purely from extracts, and a
 * reminder dated purely from extracts.
 */
@RunWith(RobolectricTestRunner::class)
class TypedExtractConsumptionTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var repository: MessageRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
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
                bundledRuleLoader = BundledRuleLoader(context, db.ruleDao(), json, NoopDataStore),
                json = json,
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun userRule(text: String) =
        runBlocking {
            val definition =
                json.decodeFromString(
                    app.clearsms.data.rules.RuleDefinition
                        .serializer(),
                    text,
                )
            db.ruleDao().insertAll(listOf(definition.toEntity(json, RuleSources.USER)))
        }

    @Test
    fun `a transaction derives from typed amount and type extracts`() =
        runBlocking {
            userRule(
                """
                {"id":"t-txn","priority":900,
                 "match":{"sender_pattern":"TSTBNK","body_pattern":"paid Rs\\.([\\d,]+\\.\\d{2}) from Acme Bank card (\\d{4})"},
                 "action":{"category":"important","sub_category":"transaction",
                   "extract":{"amount":"$1","type":"debit","account_last4":"$2","bank":"Acme Bank"}}}
                """.trimIndent(),
            )
            repository.insertIncoming("VM-TSTBNK", "You paid Rs.2,499.00 from Acme Bank card 1234 today", 1_000L)
            val tx = db.transactionDao().getAll().single()
            // Parsed once by the engine's amount grammar — commas handled there.
            assertThat(tx.amount).isEqualTo(2499.0)
            assertThat(tx.type).isEqualTo(TransactionType.DEBIT)
            assertThat(tx.accountNumber).isEqualTo("1234")
        }

    @Test
    fun `a reminder derives its due date from the typed date extract`() =
        runBlocking {
            userRule(
                """
                {"id":"t-bill","priority":900,
                 "match":{"sender_pattern":"TSTUTL","body_pattern":"charges of Rs\\.([\\d,]+), last day (\\d{2}-\\d{2}-\\d{4})"},
                 "action":{"category":"important","sub_category":"bill",
                   "extract":{"amount":"$1","due_date":"$2"}}}
                """.trimIndent(),
            )
            // "last day" is not a due-date anchor the reminder parser knows,
            // so this reminder exists ONLY because of the rule's typed
            // due_date extract.
            repository.insertIncoming("VM-TSTUTL", "Clear your water charges of Rs.850, last day 20-08-2026.", 1_000L)
            val reminder = db.reminderDao().getAll().single()
            val expected =
                LocalDate
                    .of(2026, 8, 20)
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            assertThat(reminder.dueDate).isEqualTo(expected)
            // The amount extract backs the minimum due when no min_due exists.
            assertThat(reminder.minDue).isEqualTo(850.0)
        }

    private object NoopDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }
}
