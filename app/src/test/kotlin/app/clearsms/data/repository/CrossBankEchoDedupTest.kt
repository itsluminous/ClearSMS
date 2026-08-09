package app.clearsms.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.TransactionEntity
import app.clearsms.data.rules.BundledRuleLoader
import app.clearsms.data.rules.RuleEngine
import app.clearsms.domain.categorizer.ContactLookup
import app.clearsms.domain.categorizer.MessageCategorizer
import app.clearsms.domain.categorizer.SenderIdLookup
import app.clearsms.domain.model.MerchantCategory
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
 * Cross-bank UPI echo deduplication (CR2). When someone pays the user over a
 * UPI app, TWO banks text: the user's own bank AND the UPI app's provider
 * bank - same tail, amount, direction and UPI reference, minutes apart, but
 * attributed to different banks. Exactly ONE transaction must survive, under
 * the bank with the real account relationship, and the provider bank must
 * never gain a phantom account.
 */
@RunWith(RobolectricTestRunner::class)
class CrossBankEchoDedupTest {
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

    /** A prior, unrelated HDFC message establishing the genuine account. */
    private suspend fun seedHdfcHistory(timestampMs: Long) {
        repository.insertIncoming(
            "JM-HDFCBK-S",
            "Update! INR 90,000.00 deposited in HDFC Bank A/c XX4321 on 30-JUL-26 for ACH C- SAL-EMPLOYER." +
                "Avl bal INR 95,000.00",
            timestampMs,
        )
    }

    private val hdfcCredit =
        "Credit Alert! Rs.18250.00 credited to HDFC Bank A/c XX4321 on 02-08-26 from " +
            "VPA anika.sharma-1@okaxis (UPI 987654321012)"

    private val sbiEcho =
        "Dear SBI User, your A/c X4321-credited by Rs.18250 on 02Aug26 transfer from " +
            "ANIKA SHARMA Ref No 987654321012 -SBI"

    @Test
    fun `provider echo after the real credit is collapsed and spawns no provider account`() =
        runBlocking {
            seedHdfcHistory(1_000L)
            repository.insertIncoming("AD-HDFCBK-S", hdfcCredit, 10_000L)
            // The provider echo lags well past the 90s near-dup window -
            // the shared UPI reference alone must carry the match.
            repository.insertIncoming("JD-SBIUPI", sbiEcho, 10_000L + 20 * 60_000L)

            val credits = db.transactionDao().getAll().filter { it.amount == 18250.0 }
            assertThat(credits).hasSize(1)
            assertThat(credits.single().bankName).isEqualTo("HDFC Bank")
            assertThat(credits.single().type).isEqualTo(TransactionType.CREDIT)
            val banks = db.accountDao().getAll().map { it.bankName }
            assertThat(banks).doesNotContain("State Bank of India")
        }

    @Test
    fun `echo arriving FIRST is reclaimed by the real bank and its phantom account reaped`() =
        runBlocking {
            seedHdfcHistory(1_000L)
            repository.insertIncoming("JD-SBIUPI", sbiEcho, 10_000L)
            repository.insertIncoming("AD-HDFCBK-S", hdfcCredit, 10_000L + 3 * 60_000L)

            val credits = db.transactionDao().getAll().filter { it.amount == 18250.0 }
            assertThat(credits).hasSize(1)
            assertThat(credits.single().bankName).isEqualTo("HDFC Bank")
            // The phantom SBI account created by the echo is reaped once the
            // real credit reclaims the event.
            val banks = db.accountDao().getAll().map { it.bankName }
            assertThat(banks).doesNotContain("State Bank of India")
            assertThat(banks).contains("HDFC Bank")
        }

    @Test
    fun `same tail and amount at two banks with DIFFERENT refs stays two transactions`() =
        runBlocking {
            seedHdfcHistory(1_000L)
            repository.insertIncoming("AD-HDFCBK-S", hdfcCredit, 10_000L)
            repository.insertIncoming(
                "JD-SBIUPI",
                "Dear SBI User, your A/c X4321-credited by Rs.18250 on 02Aug26 transfer from " +
                    "ROHIT VERMA Ref No 111122223333 -SBI",
                40_000L,
            )
            assertThat(db.transactionDao().getAll().filter { it.amount == 18250.0 }).hasSize(2)
        }

    @Test
    fun `ref-less cross-bank pair is vetoed when both banks genuinely hold the tail`() =
        runBlocking {
            // Both banks have independent history for tail 4321.
            seedHdfcHistory(1_000L)
            repository.insertIncoming(
                "JD-SBIUPI",
                "Dear SBI User, your A/c X4321-credited by Rs.777 on 01Aug26 transfer from " +
                    "MEERA IYER Ref No 444455556666 -SBI",
                2_000L,
            )
            // Now a ref-less same-amount credit at each bank within seconds.
            repository.insertIncoming(
                "AD-HDFCBK-S",
                "Rs.500.00 credited to HDFC Bank A/c XX4321 on 03-08-26 by cash deposit",
                100_000L,
            )
            repository.insertIncoming(
                "JD-SBIUPI",
                "Dear SBI User, your A/c X4321-credited by Rs.500 on 03Aug26 by cash deposit",
                130_000L,
            )
            assertThat(db.transactionDao().getAll().filter { it.amount == 500.0 }).hasSize(2)
        }

    // region pure tier checks

    private fun tx(
        id: Long,
        bank: String,
        reference: String? = null,
        timestamp: Long = 0L,
        amount: Double = 18250.0,
        type: TransactionType = TransactionType.CREDIT,
        account: String = "4321",
        accountId: Long? = null,
        merchant: String? = null,
    ) = TransactionEntity(
        id = id,
        amount = amount,
        type = type,
        merchantName = merchant,
        accountNumber = account,
        bankName = bank,
        accountId = accountId,
        timestamp = timestamp,
        referenceNumber = reference,
        category = MerchantCategory.OTHER,
        rawSmsId = id,
    )

    @Test
    fun `ref echo matches across banks hours apart but not past the 24h horizon`() {
        val a = tx(1, "HDFC Bank", reference = "987654321012", timestamp = 0L)
        val hoursLater = tx(2, "State Bank of India", reference = "987654321012", timestamp = 6 * 3_600_000L)
        val daysLater = tx(3, "State Bank of India", reference = "987654321012", timestamp = 25 * 3_600_000L)
        assertThat(TransactionDeduplication.isCrossBankReferenceEcho(a, hoursLater)).isTrue()
        assertThat(TransactionDeduplication.isCrossBankReferenceEcho(a, daysLater)).isFalse()
    }

    @Test
    fun `ref-less echo matches inside the tight window and not outside it`() {
        val a = tx(1, "HDFC Bank", timestamp = 0L)
        val close = tx(2, "State Bank of India", timestamp = 60_000L)
        val far = tx(3, "State Bank of India", timestamp = 5 * 60_000L)
        assertThat(TransactionDeduplication.isCrossBankNearEcho(a, close)).isTrue()
        assertThat(TransactionDeduplication.isCrossBankNearEcho(a, far)).isFalse()
    }

    @Test
    fun `rows already linked to two different accounts are two genuine accounts - never near-echo merged`() {
        val a = tx(1, "HDFC Bank", timestamp = 0L, accountId = 10L)
        val b = tx(2, "State Bank of India", timestamp = 30_000L, accountId = 20L)
        assertThat(TransactionDeduplication.isCrossBankNearEcho(a, b)).isFalse()
    }

    @Test
    fun `a self transfer between the user's own two banks never merges - directions differ`() {
        val debit = tx(1, "HDFC Bank", reference = "987654321012", type = TransactionType.DEBIT)
        val credit = tx(2, "State Bank of India", reference = "987654321012", type = TransactionType.CREDIT)
        assertThat(TransactionDeduplication.isDuplicate(debit, credit)).isFalse()
    }

    @Test
    fun `a blank tail is never cross-bank evidence`() {
        val a = tx(1, "HDFC Bank", reference = "987654321012", account = "")
        val b = tx(2, "State Bank of India", reference = "987654321012", account = "")
        assertThat(TransactionDeduplication.isCrossBankReferenceEcho(a, b)).isFalse()
        assertThat(TransactionDeduplication.isCrossBankNearEcho(a, b)).isFalse()
    }

    @Test
    fun `cross-bank survivor prefers the bank with independent evidence then the richer row`() {
        val echo = tx(1, "State Bank of India", reference = "987654321012")
        val real = tx(2, "HDFC Bank", reference = "987654321012", merchant = "anika.sharma-1@okaxis")
        assertThat(TransactionDeduplication.crossBankSurvivor(echo, real, 0, 3)).isSameInstanceAs(real)
        assertThat(TransactionDeduplication.crossBankSurvivor(echo, real, 3, 0)).isSameInstanceAs(echo)
        // Tie on evidence: the richer row (merchant present) wins.
        assertThat(TransactionDeduplication.crossBankSurvivor(echo, real, 0, 0)).isSameInstanceAs(real)
    }

    // endregion
}
