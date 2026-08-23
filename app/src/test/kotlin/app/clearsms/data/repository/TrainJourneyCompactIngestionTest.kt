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
 * End-to-end ingestion of the compact CRIS reservation SMS over the REAL
 * rules asset. The reported defect was one step past categorisation: the
 * message DID land in travel, but through the catch-all info rule that
 * extracts nothing, so no journey date existed and Alerts stayed empty.
 * All fixture values are SYNTHETIC.
 */
@RunWith(RobolectricTestRunner::class)
class TrainJourneyCompactIngestionTest {
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

    /** Message received on 23 Aug 2026, for a journey the next day. */
    private val receivedAt =
        LocalDate
            .of(2026, 8, 23)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private val reservation =
        """
        PNR-1234567890
        Trn:22345
        Dt:24-08-26
        Frm BXR to AY
        Cls:CC
        P1-C6,74
        P2-C6,9
        P3-C5,72
        P4-CNF
        Final status may change after charting
        For Enquiry/Complaint/Assistance,please dial 139 IR-CRIS
        """.trimIndent()

    @Test
    fun `the compact reservation now derives a travel alert on the journey date`() =
        runBlocking {
            val entity = repository.insertIncoming("VM-IRCTCI", reservation, receivedAt)

            assertThat(entity.category).isEqualTo(Category.IMPORTANT)
            assertThat(entity.subCategory).isEqualTo(SubCategory.TRAVEL)
            val reminder = db.reminderDao().getAll().single()
            assertThat(reminder.type).isEqualTo(ReminderType.TRAVEL)
            assertThat(dueDay(reminder.dueDate)).isEqualTo(LocalDate.of(2026, 8, 24))
            // Route and train ride the label; no departure time in this shape.
            assertThat(reminder.label).isEqualTo("Train 22345 \u00b7 BXR to AY")
        }

    @Test
    fun `a reservation derives no transaction and no money is invented from seat numbers`() =
        runBlocking {
            // "P1-C6,74" is a coach/seat, never an amount.
            repository.insertIncoming("VM-IRCTCI", reservation, receivedAt)

            assertThat(db.transactionDao().getAll()).isEmpty()
        }

    @Test
    fun `the full PNR never appears in the alert label`() =
        runBlocking {
            repository.insertIncoming("VM-IRCTCI", reservation, receivedAt)

            val label =
                db
                    .reminderDao()
                    .getAll()
                    .single()
                    .label
                    .orEmpty()
            assertThat(label).doesNotContain("1234567890")
        }

    @Test
    fun `an undated chart notice still adds nothing to Alerts`() =
        runBlocking {
            repository.insertIncoming(
                "VM-IRCTCI",
                "Chart prepared for PNR 1234567890. Happy journey - IRCTC",
                receivedAt,
            )

            assertThat(db.reminderDao().getAll()).isEmpty()
        }
}
