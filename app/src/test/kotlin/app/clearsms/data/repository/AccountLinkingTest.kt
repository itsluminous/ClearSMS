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
 * End-to-end proof that transactions are linked to accounts by BANK plus
 * last-4, never the last-4 alone. Reproduces (shapes exact, digits masked)
 * the real-device bug where a State Bank account ending 8709 listed HDFC
 * Bank transactions because three banks legitimately share that tail.
 */
@RunWith(RobolectricTestRunner::class)
class AccountLinkingTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var repository: MessageRepositoryImpl
    private lateinit var financeRepository: FinanceRepositoryImpl

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
        financeRepository =
            FinanceRepositoryImpl(
                transactionDao = db.transactionDao(),
                accountDao = db.accountDao(),
                reminderDao = db.reminderDao(),
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun ingestSharedTailCorpus() =
        runBlocking {
            // HDFC savings *8709 — two UPI debits.
            repository.insertIncoming(
                "AD-HDFCBK-S",
                "Sent Rs.500.00 From HDFC Bank A/C *8709 To someone@okaxis On 01/05/26 Ref 123456789012 " +
                    "Not You? Call 18001234567/SMS BLOCK UPI to 7308080808",
                1_000L,
            )
            repository.insertIncoming(
                "AD-HDFCBK-S",
                "Sent Rs.750.00 From HDFC Bank A/C *8709 To other@okicici On 02/05/26 Ref 123456789013 " +
                    "Not You? Call 18001234567/SMS BLOCK UPI to 7308080808",
                2_000L,
            )
            // SBI savings *8709 — one credit.
            repository.insertIncoming(
                "AD-SBIUPI-S",
                "Dear SBI User, your A/c X8709-credited by Rs.12000 on 03May26 transfer from SOME SENDER " +
                    "Ref No 123456789014 -SBI",
                3_000L,
            )
            // Federal savings *8709 — one debit with balance.
            repository.insertIncoming(
                "AD-FEDBNK-S",
                "Rs 250.00 debited from your A/c XX8709 on 04-05-2026 via UPI. Avl Bal Rs 210012.98 - Federal Bank",
                4_000L,
            )
        }

    @Test
    fun `same last-4 at three banks - each account detail shows only its own transactions`() =
        runBlocking {
            ingestSharedTailCorpus()
            val accounts = db.accountDao().getAll()
            assertThat(accounts.map { it.bankName }).containsExactly("HDFC Bank", "State Bank of India", "Federal Bank")

            val hdfc = financeRepository.observeTransactionsByAccount("8709", "HDFC Bank").first()
            val sbi = financeRepository.observeTransactionsByAccount("8709", "State Bank of India").first()
            val federal = financeRepository.observeTransactionsByAccount("8709", "Federal Bank").first()
            assertThat(hdfc).hasSize(2)
            assertThat(hdfc.map { it.bankName }).containsExactly("HDFC Bank", "HDFC Bank")
            assertThat(sbi).hasSize(1)
            assertThat(sbi.single().amount).isEqualTo(12000.0)
            assertThat(federal).hasSize(1)
            assertThat(federal.single().amount).isEqualTo(250.0)
        }

    @Test
    fun `every ingested transaction carries the id of its own bank's account row`() =
        runBlocking {
            ingestSharedTailCorpus()
            val accountByBank = db.accountDao().getAll().associateBy { it.bankName }
            for (tx in db.transactionDao().getAll()) {
                assertThat(tx.accountId).isEqualTo(accountByBank.getValue(tx.bankName).id)
            }
        }

    @Test
    fun `limited account page respects the bank boundary too`() =
        runBlocking {
            ingestSharedTailCorpus()
            val page = financeRepository.observeTransactionsByAccount("8709", "State Bank of India", 10).first()
            assertThat(page).hasSize(1)
            assertThat(page.single().bankName).isEqualTo("State Bank of India")
        }

    @Test
    fun `balance upsert lands on the right bank's row - never a same-tail sibling`() =
        runBlocking {
            ingestSharedTailCorpus()
            val accounts = db.accountDao().getAll().associateBy { it.bankName }
            // Only the Federal message carried a balance; HDFC and SBI rows
            // must not have received it despite sharing the last-4.
            assertThat(accounts.getValue("Federal Bank").lastKnownBalance).isEqualTo(210012.98)
            assertThat(accounts.getValue("State Bank of India").lastKnownBalance).isNull()
        }

    @Test
    fun `latest transaction for an account is never another bank's message`() =
        runBlocking {
            ingestSharedTailCorpus()
            // The newest *8709 transaction overall is Federal's — SBI's
            // lookup must still surface its OWN latest, not fall back by tail.
            val latest = financeRepository.latestTransactionForAccount("8709", "State Bank of India")
            assertThat(latest?.bankName).isEqualTo("State Bank of India")
            assertThat(latest?.amount).isEqualTo(12000.0)
        }

    @Test
    fun `unattributable transaction with an ambiguous tail attaches to no account`() =
        runBlocking {
            ingestSharedTailCorpus()
            val before = db.accountDao().getAll().size
            // Issuer unresolvable (payment channel), tail held by three banks.
            repository.insertIncoming(
                "JK-CREDIN",
                "Payment of INR 5,000 was received for card number 1234-56XX-XXXX-8709 on 05-May-2026 " +
                    "and you have earned 5,000 CRED coins.",
                5_000L,
            )
            assertThat(db.accountDao().getAll()).hasSize(before)
            val orphan = db.transactionDao().getAll().first { it.bankName.isEmpty() }
            assertThat(orphan.accountId).isNull()
        }

    private object NoopDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }
}
