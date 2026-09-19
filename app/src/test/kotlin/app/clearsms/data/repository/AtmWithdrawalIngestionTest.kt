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
import app.clearsms.domain.model.Category
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
 * End-to-end proof for issue #43: an ATM cash withdrawal whose account tail
 * is masked with an ELLIPSIS ("...2871") must land as a DEBIT on the bank
 * account itself - not only in the message's own detail view - with the
 * balance refreshed from the "Avlbal Amt:Rs.X" phrase, and a second
 * withdrawal must reuse the same account rather than forking a new one.
 * All digits are SYNTHETIC.
 */
@RunWith(RobolectricTestRunner::class)
class AtmWithdrawalIngestionTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var repository: MessageRepositoryImpl

    private val sender = "AD-BOBTXN-S"

    /** An earlier BOB credit that establishes the account (same ellipsis mask). */
    private val seedCredit =
        "Rs.1200.00 credited to a/c ...2871 on 10-09-2026 by a/c linked to VPA sample@fam (UPI Ref No 262533018741) - Bank of Baroda"

    private val atmWithdrawal =
        "Rs.4500.00 withdrawn from A/c ...2871 at ATM TID 9QYyyyk47 Ref.3186 " +
            "Avlbal Amt:Rs.6120.55(12-09-2026 14:05:09).In case your a/c is debited but cash is " +
            "not dispensed from the ATM, the transaction will be automatically reversed within " +
            "48 hours. TC apply. If not used by you, call 18005701-BOB"

    private val secondWithdrawal =
        "Rs.700.00 withdrawn from A/c ...2871 at ATM TID 4KZzzwq81 Ref.5417 " +
            "Avlbal Amt:Rs.5420.55(13-09-2026 09:12:41).In case your a/c is debited but cash is " +
            "not dispensed from the ATM, the transaction will be automatically reversed within " +
            "48 hours. TC apply. If not used by you, call 18005701-BOB"

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
    fun `atm withdrawal lands as a debit on the existing account with the balance updated`() =
        runBlocking {
            repository.insertIncoming(sender, seedCredit, 1_000L)
            val account = db.accountDao().getAll().single()
            assertThat(account.accountNumber).isEqualTo("2871")
            assertThat(account.bankName).isEqualTo("Bank of Baroda")

            val message = repository.insertIncoming(sender, atmWithdrawal, 2_000L)

            // Categorized as a transaction, never promotional - the trailing
            // "will be automatically reversed" advisory is not a promo.
            assertThat(message.category).isNotEqualTo(Category.PROMOTIONAL)
            assertThat(message.category).isEqualTo(Category.IMPORTANT)

            // The debit is ATTACHED to the account - the reported defect was
            // a transaction visible in the message detail but missing from
            // the account's own list (accountId was null).
            val debit = db.transactionDao().getAll().single { it.type == TransactionType.DEBIT }
            assertThat(debit.amount).isEqualTo(4500.0)
            assertThat(debit.accountId).isEqualTo(account.id)
            assertThat(debit.accountNumber).isEqualTo("2871")
            assertThat(debit.rawSmsId).isEqualTo(message.id)
            // Transaction time follows the message-date anchoring convention.
            assertThat(debit.timestamp).isEqualTo(2_000L)

            // No second account forked, and the balance came from "Avlbal Amt".
            val accounts = db.accountDao().getAll()
            assertThat(accounts).hasSize(1)
            assertThat(accounts.single().type).isEqualTo(AccountType.SAVINGS)
            assertThat(accounts.single().lastKnownBalance).isEqualTo(6120.55)
        }

    @Test
    fun `a second withdrawal reuses the account instead of forking a new one`() =
        runBlocking {
            repository.insertIncoming(sender, seedCredit, 1_000L)
            repository.insertIncoming(sender, atmWithdrawal, 2_000L)
            repository.insertIncoming(sender, secondWithdrawal, 3_000L)

            val accounts = db.accountDao().getAll()
            assertThat(accounts).hasSize(1)
            assertThat(accounts.single().lastKnownBalance).isEqualTo(5420.55)

            val debits = db.transactionDao().getAll().filter { it.type == TransactionType.DEBIT }
            assertThat(debits).hasSize(2)
            assertThat(debits.map { it.amount }).containsExactly(4500.0, 700.0)
            // Both debits sit on the SAME account row - no fork.
            assertThat(debits.map { it.accountId }.distinct().single()).isEqualTo(accounts.single().id)
        }

    @Test
    fun `the withdrawal attaches even with no earlier message for the account`() =
        runBlocking {
            repository.insertIncoming(sender, atmWithdrawal, 2_000L)

            val account = db.accountDao().getAll().single()
            assertThat(account.accountNumber).isEqualTo("2871")
            assertThat(account.bankName).isEqualTo("Bank of Baroda")
            assertThat(account.lastKnownBalance).isEqualTo(6120.55)

            val debit = db.transactionDao().getAll().single()
            assertThat(debit.type).isEqualTo(TransactionType.DEBIT)
            assertThat(debit.accountId).isEqualTo(account.id)
        }

    private object NoopDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }
}
