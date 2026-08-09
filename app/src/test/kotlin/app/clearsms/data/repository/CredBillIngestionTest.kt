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
import app.clearsms.domain.categorizer.ContactLookup
import app.clearsms.domain.categorizer.MessageCategorizer
import app.clearsms.domain.categorizer.SenderIdLookup
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.ReminderType
import app.clearsms.domain.parser.ReminderParser
import app.clearsms.domain.parser.ScamDetector
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * CRED bill-generated messages (CR3): a FALLBACK source of card due amounts.
 * The message must yield a CREDIT_CARD reminder carrying the issuing bank,
 * card tail, total and due date - and when the issuing bank's own statement
 * SMS already produced a reminder for the same card and due day, reminder
 * deduplication must collapse the two (the bank's message contributing the
 * minimum due, which CRED lacks). The pay link must not trip the scam
 * heuristics.
 */
@RunWith(RobolectricTestRunner::class)
class CredBillIngestionTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var repository: MessageRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true }

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
    }

    private val credBill =
        "Your credit card bill for Federal Bank XXXX-9024 has been generated.\n\n" +
            "Total amount: INR 17,206.53\n" +
            "Due date: August 08, 2026\n\n" +
            "Tap on the link to pay: https://link.cred.club/CREDIN/link/AbCd__12 and avoid late payment fees. - CRED"

    private fun dueDay(ms: Long?): LocalDate? = ms?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }

    @Test
    fun `cred bill ingests as a credit-card reminder with bank tail total and month-first due date`() =
        runBlocking {
            val entity = repository.insertIncoming("VA-CREDIN-S", credBill, 1_000L)
            assertThat(entity.category).isEqualTo(Category.IMPORTANT)
            val reminder = db.reminderDao().getAll().single()
            assertThat(reminder.type).isEqualTo(ReminderType.CREDIT_CARD)
            assertThat(reminder.totalDue).isEqualTo(17206.53)
            assertThat(reminder.accountLast4).isEqualTo("9024")
            assertThat(reminder.bankName).isEqualTo("Federal Bank")
            assertThat(dueDay(reminder.dueDate)).isEqualTo(LocalDate.of(2026, 8, 8))
            // A bill notice moves no money and CRED can own no account.
            assertThat(db.transactionDao().getAll()).isEmpty()
            assertThat(db.accountDao().getAll()).isEmpty()
        }

    @Test
    fun `cred reminder deduplicates against the bank's own statement for the same card and due day`() =
        runBlocking {
            // The issuing bank's own statement SMS: carries the minimum due.
            repository.insertIncoming(
                "VD-FEDBNK-S",
                "Federal Bank Credit Card XX9024 statement: Total amt due Rs 17,206.53 " +
                    "Min amt due Rs 861.00 pay by 08-08-26 to avoid charges.",
                1_000L,
            )
            repository.insertIncoming("VA-CREDIN-S", credBill, 2_000L)

            val rows = db.reminderDao().getAll()
            assertThat(rows).hasSize(2)
            val deduped = ReminderDeduplication.dedupe(rows)
            assertThat(deduped).hasSize(1)
            val merged = deduped.single()
            assertThat(merged.totalDue).isEqualTo(17206.53)
            // The bank's minimum due survives the merge - CRED lacks it.
            assertThat(merged.minDue).isEqualTo(861.0)
            assertThat(merged.accountLast4).isEqualTo("9024")
        }

    @Test
    fun `the cred pay link does not trip the scam heuristics`() {
        assertThat(ScamDetector().isScam(credBill)).isFalse()
    }

    @Test
    fun `a cred payment-received message stays a transaction and never doubles as a bill reminder`() =
        runBlocking {
            repository.insertIncoming(
                "VA-CREDIN-S",
                "Hurray! Payment of Rs. 12,345.00 was received for your Federal Bank credit card " +
                    "XXXX-9024. You earned 123 CRED coins. - CRED",
                1_000L,
            )
            assertThat(db.reminderDao().getAll()).isEmpty()
        }

    @Test
    fun `month-first dates parse and near-miss words do not`() {
        val parser = ReminderParser()
        assertThat(parser.parseDate("August 08, 2026")).isEqualTo(LocalDate.of(2026, 8, 8))
        assertThat(parser.parseDate("Aug 8 2026")).isEqualTo(LocalDate.of(2026, 8, 8))
        assertThat(parser.parseDate("Sept 5, 26")).isEqualTo(LocalDate.of(2026, 9, 5))
        // A non-month word followed by numbers is not a date.
        assertThat(parser.parseDate("Flat 20, 2026")).isNull()
    }
}
