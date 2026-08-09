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
 * Retirement-scheme contributions (CR1). An NPS units-credited SMS and an
 * EPF passbook-contribution SMS are money RECEIVED into the user's
 * retirement account - employer contributions never touch a tracked bank
 * account, so typing them "debit" fabricated spends. They must land as
 * CREDIT transactions on retirement-issuer accounts (Protean NPS / EPFO)
 * that never collide with a bank account sharing the same last-4.
 */
@RunWith(RobolectricTestRunner::class)
class RetirementContributionIngestionTest {
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

    @Test
    fun `nps units-credited contribution ingests as an INVESTMENT CREDIT on the Protean account`() =
        runBlocking {
            val entity =
                repository.insertIncoming(
                    "VA-PTNNPS-S",
                    "PRAN XX5591: Units for (MAY-2026) contribution of Rs.37,412.00 credited " +
                        "with NAV of 08/06/26 -Protean",
                    1_000L,
                )
            assertThat(entity.category).isEqualTo(Category.IMPORTANT)
            val tx = db.transactionDao().getAll().single()
            assertThat(tx.type).isEqualTo(TransactionType.CREDIT)
            assertThat(tx.amount).isEqualTo(37412.0)
            assertThat(tx.accountNumber).isEqualTo("5591")
            assertThat(tx.bankName).isEqualTo("Protean NPS")
            assertThat(tx.category).isEqualTo(MerchantCategory.INVESTMENT)
            val account = db.accountDao().getAll().single()
            assertThat(account.bankName).isEqualTo("Protean NPS")
        }

    @Test
    fun `epf passbook contribution ingests as a CREDIT with the right amount and the passbook balance`() =
        runBlocking {
            repository.insertIncoming(
                "AX-EPFOHO-S",
                "Dear XXXXXXXX2214, your passbook balance against BGBNG**************7783 is " +
                    "Rs. 21,45,332/-. Contribution of Rs. 98,704/- for due month May-26 has been received.",
                1_000L,
            )
            val tx = db.transactionDao().getAll().single()
            assertThat(tx.type).isEqualTo(TransactionType.CREDIT)
            // The contribution, never the (larger, earlier) passbook balance.
            assertThat(tx.amount).isEqualTo(98704.0)
            assertThat(tx.balance).isEqualTo(2145332.0)
            assertThat(tx.accountNumber).isEqualTo("7783")
            assertThat(tx.bankName).isEqualTo("EPFO")
            val account = db.accountDao().getAll().single()
            assertThat(account.bankName).isEqualTo("EPFO")
            assertThat(account.lastKnownBalance).isEqualTo(2145332.0)
        }

    @Test
    fun `a retirement account never collides with a bank account sharing its last-4`() =
        runBlocking {
            // A bank account whose tail equals the PRAN tail.
            repository.insertIncoming(
                "JM-HDFCBK-S",
                "Update! INR 55,000.00 deposited in HDFC Bank A/c XX5591 on 30-JUL-26 for ACH C- SALARY." +
                    "Avl bal INR 60,000.00",
                1_000L,
            )
            repository.insertIncoming(
                "VA-PTNNPS-S",
                "PRAN XX5591: Units for (MAY-2026) contribution of Rs.37,412.00 credited " +
                    "with NAV of 08/06/26 -Protean",
                2_000L,
            )
            val accounts = db.accountDao().getAll()
            assertThat(accounts.map { it.bankName }).containsExactly("HDFC Bank", "Protean NPS")
            val nps = db.transactionDao().getAll().single { it.bankName == "Protean NPS" }
            val npsAccount = accounts.single { it.bankName == "Protean NPS" }
            assertThat(nps.accountId).isEqualTo(npsAccount.id)
        }

    @Test
    fun `mutual fund SIP purchases keep their DEBIT direction - real bank money left`() =
        runBlocking {
            repository.insertIncoming(
                "VD-HDFCMF-S",
                "Dear Investor, your purchase of Rs. 25,000.00 in HDFC Flexi Cap Fund - Growth " +
                    "under Folio 12345678/91 has been processed at NAV of Rs 1,543.21. -HDFC MF",
                1_000L,
            )
            val row = db.transactionDao().getAll().single()
            assertThat(row.type).isEqualTo(TransactionType.DEBIT)
            assertThat(row.amount).isEqualTo(25000.0)
        }
}
