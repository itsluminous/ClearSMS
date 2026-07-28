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
import app.clearsms.domain.model.ReminderType
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
 * End-to-end proof of the account-creation guardrail: ingesting real
 * misattribution-shaped messages must never create accounts named after
 * merchants, payment channels or ecommerce brands, and statement notices
 * must never create transactions.
 */
@RunWith(RobolectricTestRunner::class)
class AccountGuardrailTest {
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
    fun `pluxee wallet spend creates a pluxee account never a paytm one`() =
        runBlocking {
            repository.insertIncoming(
                "VD-Pluxee",
                "Rs. 570.00 was spent from Reimbursement Wallet linked to your Pluxee Card xx2703 on " +
                    "21-12-2023 21:41:41 at Paytm. Txn no. 446270200086. Avl bal is Rs. 6301.84. Pluxee",
                1_000L,
            )
            val accounts = db.accountDao().getAll()
            assertThat(accounts).hasSize(1)
            assertThat(accounts.single().bankName).isEqualTo("Pluxee")
            assertThat(accounts.single().accountNumber).isEqualTo("2703")
        }

    @Test
    fun `cred payment with no known card creates NO account and stays unattached`() =
        runBlocking {
            repository.insertIncoming(
                "CREDCL",
                "Payment of INR 20,846.56 was received for card number 4315-81XX-XXXX-4001 on 31-May-2021 " +
                    "and you have earned 20,847 CRED coins. Simply download the app to claim them, order id.",
                1_000L,
            )
            // The issuer is unresolvable (CRED is a channel, not a bank) and
            // no named account holds this tail: a nameless account row must
            // NOT be invented. The transaction is kept but unattached.
            assertThat(db.accountDao().getAll()).isEmpty()
            val tx = db.transactionDao().getAll().single()
            assertThat(tx.accountNumber).isEqualTo("4001")
            assertThat(tx.accountId).isNull()
        }

    @Test
    fun `cred payment attaches to the sole named card holding that last-4`() =
        runBlocking {
            // A properly attributed message names the card first.
            repository.insertIncoming(
                "ICICIB",
                "INR 1,500.00 spent on ICICI Bank Card XX4001 on 30-May-2021 at Amazon. Avl Limit: INR 50,000.00.",
                500L,
            )
            repository.insertIncoming(
                "CREDCL",
                "Payment of INR 20,846.56 was received for card number 4315-81XX-XXXX-4001 on 31-May-2021 " +
                    "and you have earned 20,847 CRED coins. Simply download the app to claim them, order id.",
                1_000L,
            )
            val account = db.accountDao().getAll().single()
            assertThat(account.bankName).isEqualTo("ICICI Bank")
            // Exactly one named bank holds *4001 — the payment attaches to it.
            val payment = db.transactionDao().getAll().first { it.type.name == "CREDIT" }
            assertThat(payment.accountId).isEqualTo(account.id)
            assertThat(payment.bankName).isEmpty()
        }

    @Test
    fun `flipkart refund creates no flipkart bank account`() =
        runBlocking {
            repository.insertIncoming(
                "FLPKRT",
                "Refund Processed: The refund of Rs.3100.0 for Mi 4X 80 cm HD Ready LED Smart TV is " +
                    "successfully processed to your account ending with ***********709 and it will be " +
                    "credited by Feb 12, 2021. In case of any concern, contact us with refund reference " +
                    "number: 104116248046.",
                1_000L,
            )
            val accounts = db.accountDao().getAll()
            assertThat(accounts.map { it.bankName }).doesNotContain("Flipkart")
            // No plausible issuer and no named account holding this tail:
            // no account row of any kind may appear.
            assertThat(accounts).isEmpty()
            assertThat(
                db
                    .transactionDao()
                    .getAll()
                    .single()
                    .merchantName,
            ).isEqualTo("Flipkart")
        }

    @Test
    fun `statement notice derives a reminder but never a transaction`() =
        runBlocking {
            repository.insertIncoming(
                "ICICIB",
                "ICICI Bank Credit Card XX4001 Statement is sent to pr******it@example.com. " +
                    "Total of Rs 11,710.55 or minimum of Rs 590.00 is due by 07-AUG-26.",
                1_000L,
            )
            assertThat(db.transactionDao().getAll()).isEmpty()
            val reminder = db.reminderDao().getAll().single()
            assertThat(reminder.type).isEqualTo(ReminderType.CREDIT_CARD)
            assertThat(reminder.totalDue).isEqualTo(11710.55)
        }

    private object NoopDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }
}
