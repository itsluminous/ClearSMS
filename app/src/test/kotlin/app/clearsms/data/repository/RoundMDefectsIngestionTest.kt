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
import app.clearsms.domain.model.SubCategory
import com.google.common.truth.Truth.assertThat
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
 * End-to-end ingestion of the round-M defect fixtures over the REAL bundled
 * rules asset. All fixture values (names, PNRs, consumer numbers, tracking
 * ids, links) are SYNTHETIC.
 */
@RunWith(RobolectricTestRunner::class)
class RoundMDefectsIngestionTest {
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

    // region defect 1: rewards pitch fabricated a Rs 100 debit

    @Test
    fun `rewards pitch categorizes promotional with zero transaction rows`() =
        runBlocking {
            val entity =
                repository.insertIncoming(
                    "JD-KOTAKB-P",
                    "Still spending without rewards? Link Kotak UPI Rupay CC to your UPI app & " +
                        "earn 3 pts on every Rs 100 spent. Apply: https://1.example.bank.in/KOTAKB/AbCdEf T&C",
                    1_000L,
                )
            assertThat(entity.category).isEqualTo(Category.PROMOTIONAL)
            assertThat(db.transactionDao().getAll()).isEmpty()
            assertThat(db.accountDao().getAll()).isEmpty()
        }

    @Test
    fun `a real Kotak debit still derives its transaction`() =
        runBlocking {
            repository.insertIncoming(
                "JD-KOTAKB-S",
                "Sent Rs.500.00 from Kotak Bank AC X4321 to shop@upi on 10-08-26. UPI Ref 522212345678. " +
                    "Not you? Call 18002662666.",
                1_000L,
            )
            val tx = db.transactionDao().getAll().single()
            assertThat(tx.amount).isEqualTo(500.0)
            assertThat(tx.bankName).isEqualTo("Kotak Mahindra Bank")
        }

    // endregion

    // region defect 2: BESCOM bill-generated via a bank payment route

    @Test
    fun `generated electricity bill ingests as an undated bill reminder attributed to the board`() =
        runBlocking {
            val entity =
                repository.insertIncoming(
                    "JD-HDFCBK-S",
                    "Your Bangalore Ele.... Ltd (BESCOM) (3011122233) bill of Rs 3582.00 is generated. " +
                        "Pay now on PayZapp. https://1.example.bank.in/HDFCBK/s/AbCdEfGh",
                    1_000L,
                )
            assertThat(entity.category).isEqualTo(Category.IMPORTANT)
            val reminder = db.reminderDao().getAll().single()
            assertThat(reminder.totalDue).isEqualTo(3582.0)
            assertThat(reminder.dueDate).isNull()
            // The BODY names the biller; the HDFC/PayZapp route must not win.
            assertThat(reminder.bankName).isEqualTo("BESCOM")
            assertThat(reminder.label).isEqualTo("Electricity bill")
            // A bill notice moves no money.
            assertThat(db.transactionDao().getAll()).isEmpty()
            // Undated reminders surface as upcoming in Alerts.
            assertThat(
                db
                    .reminderDao()
                    .observeUpcoming(2_000_000L)
                    .first()
                    .single()
                    .id,
            ).isEqualTo(reminder.id)
        }

    @Test
    fun `paid-bill confirmation produces no reminder`() =
        runBlocking {
            repository.insertIncoming(
                "JD-HDFCBK-S",
                "Your BESCOM bill of Rs 3582.00 has been paid successfully via PayZapp. Ref 522298765432.",
                1_000L,
            )
            assertThat(db.reminderDao().getAll()).isEmpty()
        }

    // endregion

    // region defect 3: card dispatched via courier with AWB

    @Test
    fun `card dispatch via courier ingests as an undated delivery with courier and tracking id`() =
        runBlocking {
            val entity =
                repository.insertIncoming(
                    "JD-BOBCRD-S",
                    "Congrats, Your BOBCARD is dispatched via Bluedart AWB 31198765432. " +
                        "Track here https://bluedart.com/?31198765432. Download the BOBCARD app to activate your card quickly.",
                    1_000L,
                )
            assertThat(entity.category).isEqualTo(Category.IMPORTANT)
            assertThat(entity.subCategory).isEqualTo(SubCategory.DELIVERY)
            val reminder = db.reminderDao().getAll().single()
            assertThat(reminder.type).isEqualTo(ReminderType.DELIVERY)
            assertThat(reminder.bankName).isEqualTo("Blue Dart")
            assertThat(reminder.label).isEqualTo("31198765432")
            // No arrival date was stated: the card is undated, never a fake ETA.
            assertThat(reminder.dueDate).isNull()
            assertThat(db.transactionDao().getAll()).isEmpty()
        }

    // endregion
}
