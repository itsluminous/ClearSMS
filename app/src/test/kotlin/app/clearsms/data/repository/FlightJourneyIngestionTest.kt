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
 * End-to-end ingestion of bundled FLIGHT rules over the REAL rules asset:
 * a dated airline booking derives a TRAVEL Alerts card on the journey date
 * through the exact path train tickets use, while undated gate/terminal
 * notices stay out of Alerts. All fixture values are SYNTHETIC.
 */
@RunWith(RobolectricTestRunner::class)
class FlightJourneyIngestionTest {
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

    @Test
    fun `airline booking ingests as a travel alert due on the journey date`() =
        runBlocking {
            val entity =
                repository.insertIncoming(
                    "VM-INDIGO",
                    "Dear Mr Kumar, we are happy to confirm your booking under PNR - QW8ZX2, " +
                        "22 Aug 26, from BLR(T1) to DEL, 6E 2431 at 06:40 hrs.",
                    1_000L,
                )
            assertThat(entity.category).isEqualTo(Category.IMPORTANT)
            assertThat(entity.subCategory).isEqualTo(SubCategory.TRAVEL)
            val reminder = db.reminderDao().getAll().single()
            assertThat(reminder.type).isEqualTo(ReminderType.TRAVEL)
            assertThat(dueDay(reminder.dueDate)).isEqualTo(LocalDate.of(2026, 8, 22))
            assertThat(reminder.label).isEqualTo("Flight 6E 2431 \u00b7 BLR(T1) to DEL \u00b7 dep 06:40")
            assertThat(db.transactionDao().getAll()).isEmpty()
        }

    @Test
    fun `route-and-DOJ booking shape derives its travel alert`() =
        runBlocking {
            repository.insertIncoming(
                "VM-AIRIND",
                "Air India Your booking is confirmed. PNR AB1CD2, DEL-BOM, 30 Aug 26, 18.25 dep.",
                1_000L,
            )
            val reminder = db.reminderDao().getAll().single()
            assertThat(reminder.type).isEqualTo(ReminderType.TRAVEL)
            assertThat(dueDay(reminder.dueDate)).isEqualTo(LocalDate.of(2026, 8, 30))
            assertThat(reminder.label).isEqualTo("DEL-BOM \u00b7 dep 18.25")
        }

    @Test
    fun `gate change notice categorizes travel but derives no alert card`() =
        runBlocking {
            val entity =
                repository.insertIncoming(
                    "VM-TRPSRC",
                    "TripSource: The gate has changed for your flight to Hyderabad (6E 1234). " +
                        "It is now departing from Terminal 1, Gate 22.",
                    1_000L,
                )
            assertThat(entity.category).isEqualTo(Category.IMPORTANT)
            assertThat(entity.subCategory).isEqualTo(SubCategory.TRAVEL)
            assertThat(db.reminderDao().getAll()).isEmpty()
        }
}
