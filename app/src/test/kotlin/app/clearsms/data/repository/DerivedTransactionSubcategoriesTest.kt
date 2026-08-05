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
 * End-to-end proof that investment (NPS, mutual-fund SIP) and prepaid-recharge
 * rules derive real transactions through the full ingestion pipeline (real
 * bundled rules), while balance-only statements never do.
 */
@RunWith(RobolectricTestRunner::class)
class DerivedTransactionSubcategoriesTest {
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
    fun `nps contribution derives one investment credit with a protean account`() =
        runBlocking {
            repository.insertIncoming(
                "VM-NSDLNP",
                "PRAN XX8227: Units for (APR-2026) contribution of Rs.44,236.00 credited " +
                    "with NAV of 07/05/26 -Protean",
                1_000L,
            )
            val tx = db.transactionDao().getAll().single()
            assertThat(tx.amount).isEqualTo(44236.0)
            // Money RECEIVED into the retirement account: employer NPS
            // contributions never leave a tracked bank account, so a debit
            // here fabricated spend (CR: retirement credits).
            assertThat(tx.type).isEqualTo(TransactionType.CREDIT)
            assertThat(tx.merchantName).isEqualTo("NPS")
            assertThat(tx.category).isEqualTo(MerchantCategory.INVESTMENT)
            // The user asked for NPS to be treated as an account: the PRAN
            // tail identifies it and Protean (the NPS CRA) is its issuer.
            val account = db.accountDao().getAll().single()
            assertThat(account.accountNumber).isEqualTo("8227")
            assertThat(account.bankName).isEqualTo("Protean NPS")
        }

    @Test
    fun `nps investment value statement derives no transaction`() =
        runBlocking {
            repository.insertIncoming(
                "VM-NSDLNP",
                "Investment value in Tier I (PRANXX8227) as on 30.06.2026 is Rs 10,51,328.93. " +
                    "For details login to CRA system -Protean",
                1_000L,
            )
            assertThat(db.transactionDao().getAll()).isEmpty()
        }

    @Test
    fun `sip purchase amount-first shape derives one debit titled with the fund`() =
        runBlocking {
            repository.insertIncoming(
                "VM-IPRUMF",
                "Dear Investor, Your SIP Purchase of Rs.99,995.00 in Folio 14984542 in " +
                    "Focused Equity Fund - DP Growth for 901.587 units has been processed " +
                    "for NAV of Rs.110.91 on 20-Jul-2026 - IPRUMF",
                1_000L,
            )
            val tx = db.transactionDao().getAll().single()
            assertThat(tx.amount).isEqualTo(99995.0)
            assertThat(tx.type).isEqualTo(TransactionType.DEBIT)
            assertThat(tx.merchantName).isEqualTo("Focused Equity Fund")
            assertThat(tx.category).isEqualTo(MerchantCategory.INVESTMENT)
        }

    @Test
    fun `sip purchase fund-first shape derives one debit titled with the fund`() =
        runBlocking {
            repository.insertIncoming(
                "VM-HDFCMF",
                "Your SIP Purchase in Folio 17766840/10 under HDFC Flexi Cap Fund-DP-Growth " +
                    "for Rs. 79,996.00 has been processed at the NAV of 2240.661 for 35.702 " +
                    "units and 10-Jul-2026.",
                1_000L,
            )
            val tx = db.transactionDao().getAll().single()
            assertThat(tx.amount).isEqualTo(79996.0)
            assertThat(tx.type).isEqualTo(TransactionType.DEBIT)
            assertThat(tx.merchantName).isEqualTo("HDFC Flexi Cap Fund")
            assertThat(tx.category).isEqualTo(MerchantCategory.INVESTMENT)
        }

    @Test
    fun `sip allotment dash shape derives one debit titled with the fund`() =
        runBlocking {
            repository.insertIncoming(
                "VM-CRMF",
                "Your SIP - Rs.10999.45 in Folio XXXXXXX6073 - CR Elss Tax Saver Fund - " +
                    "Direct Growth - 54.388 units allotted at NAV of Rs.202.24 on 10/07/2026 - CRMF",
                1_000L,
            )
            val tx = db.transactionDao().getAll().single()
            assertThat(tx.amount).isEqualTo(10999.45)
            assertThat(tx.type).isEqualTo(TransactionType.DEBIT)
            assertThat(tx.merchantName).isEqualTo("CR Elss Tax Saver Fund")
            assertThat(tx.category).isEqualTo(MerchantCategory.INVESTMENT)
        }

    @Test
    fun `prepaid recharge success derives one recharge debit`() =
        runBlocking {
            repository.insertIncoming(
                "VD-RCHRGE",
                "Hi, Your Prepaid recharge of Rs. 1198.0 is success against " +
                    "Order Id 7481038541423177728. Thank you!",
                1_000L,
            )
            val tx = db.transactionDao().getAll().single()
            assertThat(tx.amount).isEqualTo(1198.0)
            assertThat(tx.type).isEqualTo(TransactionType.DEBIT)
            // An unresolved sender id is never used as a title, so the row
            // carries no merchant and the UI keeps its generic wording.
            assertThat(tx.merchantName).isNull()
            assertThat(tx.category).isEqualTo(MerchantCategory.RECHARGE)
            // Recharges carry no account tail — no account row may appear.
            assertThat(db.accountDao().getAll()).isEmpty()
        }

    private object NoopDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }
}
