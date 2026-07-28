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

/**
 * Rule-supplied merchant extracts go through the SAME normalization as the
 * parser's own narration cleanup, exercised through the rule-first production
 * path (real bundled rules) — the earlier parser-only test missed exactly this
 * path, which is how the raw "XX6894- RD Installment-Jul 2026" title shipped.
 */
@RunWith(RobolectricTestRunner::class)
class RuleMerchantPrecedenceTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var repository: MessageRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val json = Json { ignoreUnknownKeys = true }
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

    @Test
    fun `hdfc rd installment via the rule path is titled RD Installment`() =
        runBlocking {
            val message =
                repository.insertIncoming(
                    "VM-HDFCBK",
                    "UPDATE: INR 13,000.00 debited from HDFC Bank XX8709 on 16-JUL-26. " +
                        "Info: XXXXXXXXXX6894- RD Installment-Jul 2026. Avl bal:INR 1,07,721.74",
                    1_000L,
                )
            val tx = db.transactionDao().getAll().single()
            // The rule's raw Info capture ("XXXXXXXXXX6894- RD Installment-Jul
            // 2026") must be normalized, not persisted verbatim.
            assertThat(tx.merchantName).isEqualTo("RD Installment")
            assertThat(tx.amount).isEqualTo(13000.0)
            assertThat(tx.type).isEqualTo(TransactionType.DEBIT)
            assertThat(tx.accountNumber).isEqualTo("8709")
            assertThat(tx.bankName).isEqualTo("HDFC Bank")
            // The stored extract audit trail must carry the CLEAN title too —
            // the raw capture used to be re-injected into extractedDataJson.
            assertThat(message.extractedDataJson).contains("\"merchant\":\"RD Installment\"")
            assertThat(message.extractedDataJson).doesNotContain("6894")
        }

    @Test
    fun `pure reference info narration falls back instead of a digit-run title`() =
        runBlocking {
            repository.insertIncoming(
                "VM-HDFCBK",
                "UPDATE: INR 500.00 debited from HDFC Bank XX8709 on 16-JUL-26. " +
                    "Info: UPI-519876543210. Avl bal:INR 1,000.00",
                1_000L,
            )
            val tx = db.transactionDao().getAll().single()
            // "UPI-519876543210" is a reference, not a merchant — the raw
            // capture must never surface as a title.
            assertThat(tx.merchantName ?: "").doesNotContain("519876543210")
        }

    @Test
    fun `clean rule-supplied merchant passes through unchanged`() =
        runBlocking {
            repository.insertIncoming(
                "VM-HDFCBK",
                "UPDATE: INR 249.00 debited from HDFC Bank XX8709 on 16-JUL-26. " +
                    "Info: Netflix Subscription. Avl bal:INR 9,000.00",
                1_000L,
            )
            val tx = db.transactionDao().getAll().single()
            assertThat(tx.merchantName).isEqualTo("Netflix Subscription")
        }

    private object NoopDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }
}
