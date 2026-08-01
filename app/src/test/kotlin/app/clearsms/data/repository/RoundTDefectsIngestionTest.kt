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

/**
 * End-to-end ingestion of the round-T defect fixtures over the REAL bundled
 * rules asset: the full rule → typed extract → parser-merge → derived-row
 * pipeline, asserting what lands in the reminders/transactions tables and
 * in extractedDataJson (what the parsed notification renders).
 */
@RunWith(RobolectricTestRunner::class)
class RoundTDefectsIngestionTest {
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

    private fun details(raw: String?): Map<String, String> = raw?.let { json.decodeFromString<Map<String, String>>(it) } ?: emptyMap()

    @Test
    fun `autopay mandate ingests as a reminder with the total the payee label and no transaction`() =
        runBlocking {
            val entity =
                repository.insertIncoming(
                    "VM-ICICIB",
                    "ICICI Bank SAVINGS Account XX222 will be debited for Rs 59.00 on 03-Jul-26 towards " +
                        "Autopay for YouTube, UPI Mandate, Unique Mandate Number 4e6c1da5c2cbf333e063b22fb00aef94@oksbi",
                    1_000L,
                )
            val reminder = db.reminderDao().getAll().single()
            assertThat(reminder.totalDue).isEqualTo(59.0)
            assertThat(reminder.label).isEqualTo("YouTube autopay")
            assertThat(db.transactionDao().getAll()).isEmpty()
            // The stored due date is normalized to ISO for the notification.
            assertThat(details(entity.extractedDataJson)["due_date"]).isEqualTo("2026-07-03")
            assertThat(details(entity.extractedDataJson)["merchant"]).isEqualTo("YouTube")
        }

    @Test
    fun `premium notice ingests as an insurance reminder with its amount and no phantom debit`() =
        runBlocking {
            repository.insertIncoming(
                "JD-ICICIP",
                "Dear valued customer, premium due on 15-Jul-26 for your ICICIPru policy ICICI Pru iProtect Smart " +
                    "policy no. H4847657 of Rs. 1250 will be deducted as per standing instructions. " +
                    "Kindly ignore if paid. T&C apply.",
                1_000L,
            )
            val reminder = db.reminderDao().getAll().single()
            assertThat(reminder.totalDue).isEqualTo(1250.0)
            assertThat(reminder.label).isEqualTo("ICICI Pru iProtect Smart")
            // "Kindly ignore if paid" used to fake a Rs 1250 debit.
            assertThat(db.transactionDao().getAll()).isEmpty()
        }

    @Test
    fun `card bill ingests with total and minimum plus the account bank and label for the notification`() =
        runBlocking {
            val entity =
                repository.insertIncoming(
                    "AX-AXISBK",
                    "Payment of INR 14683.41 for Axis Bank Credit Card no. XX5106 is due on 04-08-26 " +
                        "with minimum amount due of INR 881. Ignore if paid.",
                    1_000L,
                )
            val reminder = db.reminderDao().getAll().single()
            assertThat(reminder.totalDue).isEqualTo(14683.41)
            assertThat(reminder.minDue).isEqualTo(881.0)
            val map = details(entity.extractedDataJson)
            assertThat(map["total_due"]).isEqualTo("14683.41")
            assertThat(map["account_last4"]).isEqualTo("5106")
            assertThat(map["bank"]).isEqualTo("Axis Bank")
            assertThat(map["label"]).isEqualTo("Axis Bank Credit Card")
            assertThat(db.transactionDao().getAll()).isEmpty()
        }

    @Test
    fun `transaction otp ingests as an OTP with the code and derives no transaction`() =
        runBlocking {
            val entity =
                repository.insertIncoming(
                    "AD-AXISBK",
                    "413423 is SECRET OTP for txn of INR 1205.23 on Axis Bank card XX0266 at AIRTEL PAY on " +
                        "01-08-26 18:57:01. OTP valid for 5 mins. Please do not share this OTP.",
                    1_000L,
                )
            assertThat(entity.category).isEqualTo(Category.OTP)
            assertThat(entity.extractedOtp).isEqualTo("413423")
            assertThat(db.transactionDao().getAll()).isEmpty()
        }
}
