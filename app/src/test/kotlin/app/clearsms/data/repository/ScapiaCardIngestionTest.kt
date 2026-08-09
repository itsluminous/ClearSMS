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
import app.clearsms.domain.model.AccountType
import app.clearsms.domain.model.ReminderType
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
 * End-to-end Scapia Federal recognition and body-bank attribution:
 * digit-less card spends land on ONE issuer-keyed Scapia card, declines and
 * notices never become transactions, the statement becomes a card bill
 * reminder, and a counterparty bank in IMPS narration never steals an HDFC
 * credit - nor do CIBIL/CERSAI mentions create a Federal Bank account.
 */
@RunWith(RobolectricTestRunner::class)
class ScapiaCardIngestionTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var repository: MessageRepositoryImpl

    private val scapiaSpend =
        "Hi! Your txn of \u20b95,696.87 at Discover Qatar Doha Qa on your Scapia Federal Visa credit card " +
            "was successful. Not you? Go to Scapia support on the app.- Federal Bank"

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
    fun `digit-less scapia spend becomes a debit on an issuer-keyed credit card`() =
        runBlocking {
            repository.insertIncoming("TX-FEDSCP-S", scapiaSpend, 1_000L)

            val tx = db.transactionDao().getAll().single()
            assertThat(tx.type).isEqualTo(TransactionType.DEBIT)
            assertThat(tx.amount).isEqualTo(5696.87)
            assertThat(tx.merchantName).isEqualTo("Discover Qatar Doha Qa")
            assertThat(tx.bankName).isEqualTo("Scapia Federal")

            val account = db.accountDao().getAll().single()
            assertThat(account.bankName).isEqualTo("Scapia Federal")
            assertThat(account.type).isEqualTo(AccountType.CREDIT_CARD)
            assertThat(account.accountNumber).isEqualTo("SCAPIAFEDERAL")
            assertThat(tx.accountId).isEqualTo(account.id)
        }

    @Test
    fun `two digit-less spends share ONE scapia card`() =
        runBlocking {
            repository.insertIncoming("TX-FEDSCP-S", scapiaSpend, 1_000L)
            repository.insertIncoming(
                "VM-FEDSCP-S",
                "Hi! Your txn of \u20b942.00 at The Kowloon Moto Hongkong on your Scapia Federal Visa credit " +
                    "card was successful. Not you? Go to Scapia support on the app.- Federal Bank",
                2_000L,
            )
            val accounts = db.accountDao().getAll()
            assertThat(accounts).hasSize(1)
            val ids =
                db
                    .transactionDao()
                    .getAll()
                    .map { it.accountId }
                    .distinct()
            assertThat(ids).containsExactly(accounts.single().id)
            assertThat(db.transactionDao().getAll()).hasSize(2)
        }

    @Test
    fun `a digit-less spend attaches to an existing sole scapia card instead of inventing a second`() =
        runBlocking {
            // A digit-keyed Scapia card already exists (a future template change).
            db.accountDao().insert(
                app.clearsms.data.db.AccountEntity(
                    accountNumber = "9876",
                    bankName = "Scapia Federal",
                    type = AccountType.CREDIT_CARD,
                    lastKnownBalance = null,
                    lastUpdated = 500L,
                ),
            )
            repository.insertIncoming("TX-FEDSCP-S", scapiaSpend, 1_000L)

            val accounts = db.accountDao().getAll()
            assertThat(accounts).hasSize(1)
            assertThat(accounts.single().accountNumber).isEqualTo("9876")
            assertThat(
                db
                    .transactionDao()
                    .getAll()
                    .single()
                    .accountId,
            ).isEqualTo(accounts.single().id)
        }

    @Test
    fun `declined scapia txn never becomes a transaction`() =
        runBlocking {
            repository.insertIncoming(
                "VM-FEDSCP-S",
                "Txn for \u20b942.00 at Red And White Fleet Retai on your Scapia Federal Visa credit card " +
                    "declined due to an invalid PIN. Retry your payment with the correct PIN. - Federal Bank",
                1_000L,
            )
            assertThat(db.transactionDao().getAll()).isEmpty()
            assertThat(db.accountDao().getAll()).isEmpty()
        }

    @Test
    fun `scapia statement becomes a credit card bill reminder not a transaction`() =
        runBlocking {
            repository.insertIncoming(
                "VM-FEDSCP-S",
                "Hi! Your Scapia Federal Credit Card statement for JULY-2026 is here. " +
                    "Check your statement on the app and pay by 05-08-2026 - Federal Bank",
                1_000L,
            )
            assertThat(db.transactionDao().getAll()).isEmpty()
            val reminder = db.reminderDao().getAll().single()
            assertThat(reminder.type).isEqualTo(ReminderType.CREDIT_CARD)
            assertThat(reminder.dueDate).isNotNull()
        }

    @Test
    fun `enabled-transactions notice with a decoy last-4 creates nothing`() =
        runBlocking {
            repository.insertIncoming(
                "VM-FEDSCP-S",
                "Hi! You've enabled domestic ECOM transactions on your Scapia Federal Visa credit card " +
                    "ending with 1234 on 2026-07-01 12:00:00. -Federal Bank",
                1_000L,
            )
            assertThat(db.transactionDao().getAll()).isEmpty()
            assertThat(db.accountDao().getAll()).isEmpty()
        }

    @Test
    fun `tap-to-pay advisory with a decoy amount creates nothing`() =
        runBlocking {
            repository.insertIncoming(
                "VM-FEDSCP-S",
                "Hi, you can't tap to pay for transactions above Rs.5,000. Retry the payment by " +
                    "inserting your Scapia Federal Credit Card into the POS machine. -Federal Bank",
                1_000L,
            )
            assertThat(db.transactionDao().getAll()).isEmpty()
            assertThat(db.accountDao().getAll()).isEmpty()
        }

    @Test
    fun `hdfc credit with federal bank in IMPS narration stays an HDFC transaction`() =
        runBlocking {
            repository.insertIncoming(
                "JM-HDFCBK-S",
                "Received!\nINR 1.00 in HDFC Bank A/c xx8709\nOn 16-06-26\n" +
                    "For IMPS -Federal bank- 616715401395\nAvl bal INR 2,10,012.98",
                1_000L,
            )
            val tx = db.transactionDao().getAll().single()
            assertThat(tx.type).isEqualTo(TransactionType.CREDIT)
            assertThat(tx.amount).isEqualTo(1.0)
            assertThat(tx.bankName).isEqualTo("HDFC Bank")
            assertThat(tx.accountNumber).isEqualTo("8709")

            val account = db.accountDao().getAll().single()
            assertThat(account.bankName).isEqualTo("HDFC Bank")
            assertThat(account.accountNumber).isEqualTo("8709")
        }

    @Test
    fun `aggregator body bank still wins - a card payment app naming an axis card lands on axis`() =
        runBlocking {
            repository.insertIncoming(
                "JK-CREDIN",
                "Payment of INR 12,345.00 was received for your Axis Bank credit card XX4321 on 12-Jan-2026. " +
                    "Transaction ref 987654321012.",
                1_000L,
            )
            val account = db.accountDao().getAll().single()
            assertThat(account.bankName).isEqualTo("Axis Bank")
            assertThat(account.accountNumber).isEqualTo("4321")
        }

    @Test
    fun `cibil score check naming federal bank creates no account and no transaction`() =
        runBlocking {
            repository.insertIncoming(
                "VA-CIBILA-S",
                "Your CIBIL Score & Report was checked by FEDERAL BANK ECN:12345678901 on 2026-06-14 " +
                    "10:12:00. Know More? Visit https://example.invalid -CIBIL",
                1_000L,
            )
            assertThat(db.accountDao().getAll()).isEmpty()
            assertThat(db.transactionDao().getAll()).isEmpty()
        }

    @Test
    fun `cersai record fetch naming federal bank creates no account`() =
        runBlocking {
            repository.insertIncoming(
                "BZ-CKYCR-S",
                "Dear CUSTOMER, your CKYCRR record bearing reference 12345678901234 was fetched by " +
                    "THE FEDERAL BANK LTD on 14/06/2026.-CERSAI",
                1_000L,
            )
            assertThat(db.accountDao().getAll()).isEmpty()
            assertThat(db.transactionDao().getAll()).isEmpty()
        }

    @Test
    fun `near-miss - a digit-less savings debit still creates no account`() =
        runBlocking {
            // No card wording, no last-4 anywhere: the issuer-keyed exception
            // must not fire for ordinary bank debits.
            repository.insertIncoming(
                "AD-HDFCBK-S",
                "Rs.500.00 debited towards Some Merchant on 12-07-26. Call 18002586161 for disputes.",
                1_000L,
            )
            val tx = db.transactionDao().getAll().singleOrNull()
            if (tx != null) {
                assertThat(tx.accountId).isNull()
            }
            assertThat(db.accountDao().getAll()).isEmpty()
        }

    private object NoopDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }
}
