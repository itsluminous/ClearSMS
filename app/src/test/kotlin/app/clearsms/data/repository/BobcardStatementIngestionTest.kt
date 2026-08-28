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
 * End-to-end ingestion of a BOBCARD statement over the REAL rules asset: the
 * reported defect was a credit-card statement filed as PROMOTIONAL with no
 * Alerts reminder. It must now be an IMPORTANT bill that derives a
 * CREDIT_CARD reminder carrying both the total and the minimum due.
 * All fixture values are SYNTHETIC.
 */
@RunWith(RobolectricTestRunner::class)
class BobcardStatementIngestionTest {
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

    private fun dueDay(ms: Long?): LocalDate? = ms?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }

    private val receivedAt =
        LocalDate
            .of(2026, 8, 28)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private val statement =
        "Statement for BOBCARD **1234 for AUG26 is generated. Pay Total: Rs 4210.5 or " +
            "Min Due: Rs 310 by 13-09-26. View/Download Statement on Mobile App. Pay via " +
            "bobcard.io/App or InstaPay/Net Banking. Avoid 3rd-party apps for timely " +
            "processing. Know more: bobcard.io/Pymt."

    @Test
    fun `the statement ingests as a bill with a reminder on the due date`() =
        runBlocking {
            val entity = repository.insertIncoming("VM-BOBCRD", statement, receivedAt)

            assertThat(entity.category).isEqualTo(Category.IMPORTANT)
            assertThat(entity.subCategory).isEqualTo(SubCategory.BILL)
            val reminder = db.reminderDao().getAll().single()
            assertThat(reminder.type).isEqualTo(ReminderType.CREDIT_CARD)
            assertThat(dueDay(reminder.dueDate)).isEqualTo(LocalDate.of(2026, 9, 13))
            assertThat(reminder.totalDue).isEqualTo(4210.5)
            assertThat(reminder.minDue).isEqualTo(310.0)
            assertThat(reminder.accountLast4).isEqualTo("1234")
        }

    @Test
    fun `a statement derives no spend transaction`() =
        runBlocking {
            // A bill is an obligation, not a spend: the total must never be
            // booked as a debit on the card.
            repository.insertIncoming("VM-BOBCRD", statement, receivedAt)

            assertThat(db.transactionDao().getAll()).isEmpty()
        }

    @Test
    fun `the total stays at or above the minimum due`() =
        runBlocking {
            repository.insertIncoming("VM-BOBCRD", statement, receivedAt)

            val reminder = db.reminderDao().getAll().single()
            assertThat(reminder.totalDue!!).isAtLeast(reminder.minDue!!)
        }

    @Test
    fun `a bobcard offer still derives no reminder`() =
        runBlocking {
            repository.insertIncoming(
                "VM-BOBCRD",
                "Exciting offer! Get 10% cashback up to Rs 500 on your BOBCARD this festive " +
                    "season. Shop now: bobcard.io/Offers",
                receivedAt,
            )

            assertThat(db.reminderDao().getAll()).isEmpty()
        }
}
