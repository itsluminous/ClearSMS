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

/**
 * End-to-end ingestion of UPI collect / payment-request fixtures over the
 * REAL bundled rules asset. A collect request ("You've received an IPO
 * request from X for up to Rs.Y") used to land as a green + ₹Y CREDIT; it
 * must derive NO transaction row, categorize as an IMPORTANT bank alert,
 * and carry only the unsigned `requested_amount` key in extractedDataJson -
 * never `amount`/`type` (what the parsed notification renders signed).
 */
@RunWith(RobolectricTestRunner::class)
class CollectRequestIngestionTest {
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
    fun `IPO collect request ingests with no transaction and an unsigned requested amount`() =
        runBlocking {
            val entity =
                repository.insertIncoming(
                    "PHONPE",
                    "You've received an IPO request from EXAMPLE TRANSMISSION LIMITED for up to Rs.14807. " +
                        "Click to accept. https://phone.pe/PHONPE/x8m9nrb3",
                    1_000L,
                )
            // No money moved: never a transaction row.
            assertThat(db.transactionDao().getAll()).isEmpty()
            assertThat(entity.category).isEqualTo(Category.IMPORTANT)
            assertThat(entity.subCategory).isEqualTo(SubCategory.BANK_ALERT)
            val map = details(entity.extractedDataJson)
            // The requested figure rides its own unsigned key; the signed
            // keys the notifier colors green/red must be absent.
            assertThat(map["requested_amount"]).isEqualTo("14807.0")
            assertThat(map).doesNotContainKey("amount")
            assertThat(map).doesNotContainKey("type")
        }

    @Test
    fun `executed mandate debit after approval still ingests as a real debit`() =
        runBlocking {
            repository.insertIncoming(
                "VM-HDFCBK",
                "Rs.14807.00 debited from A/c XX1234 towards EXAMPLE TRANSMISSION LIMITED. Ref 519912345678.",
                1_000L,
            )
            val tx = db.transactionDao().getAll().single()
            assertThat(tx.amount).isEqualTo(14807.0)
        }
}
