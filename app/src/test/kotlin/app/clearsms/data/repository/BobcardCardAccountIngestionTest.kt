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
import java.time.LocalDate
import java.time.ZoneId

/**
 * End-to-end ingestion of a BOBCARD credit card's lifecycle over the REAL
 * rules asset: the reported defect was the card filed under BANK ACCOUNTS
 * in Finance, because "your BOBCARD ending 1234" never read as a card and
 * the account was created as SAVINGS. The spend must create ONE
 * CREDIT_CARD account under Bank of Baroda carrying the issuer-reported
 * available limit; the payment must land on that same card as a credit;
 * the statement must still derive its bill reminder and never a spend.
 * All fixture values are SYNTHETIC.
 */
@RunWith(RobolectricTestRunner::class)
class BobcardCardAccountIngestionTest {
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

    private fun at(date: LocalDate): Long = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val spend =
        "ALERT: INR 1,462.35 is spent on your BOBCARD ending 5081 at Green Leaf Organics on 19-08-2026. " +
            "Available credit limit is Rs 1,48,537.65, Current outstanding is Rs 0.00. Not you? Call 18002090 (toll-free)"

    private val statement =
        "Statement for BOBCARD **5081 for AUG26 is generated. Pay Total: Rs 1462.35 or Min Due: Rs 200 by 13-09-26. " +
            "View/Download Statement on Mobile App. Pay via bobcard.io/App or InstaPay. Know more: bobcard.io/Pymt."

    private val payment =
        "Update: Payment of Rs 1462.35 received for your BOBCARD ending 5081 on 2026-09-02. " +
            "Thank you. Know more: bobcard.io/Pymt"

    @Test
    fun `the spend creates one CREDIT_CARD account under Bank of Baroda with the available limit`() =
        runBlocking {
            repository.insertIncoming("VM-BOBCRD", spend, at(LocalDate.of(2026, 8, 19)))

            val account = db.accountDao().getAll().single()
            assertThat(account.type).isEqualTo(AccountType.CREDIT_CARD)
            assertThat(account.bankName).isEqualTo("Bank of Baroda")
            assertThat(account.accountNumber).isEqualTo("5081")
            assertThat(account.availableLimit).isEqualTo(148537.65)
            // "Current outstanding" is not a stored field: Finance derives it
            // from total minus available, so nothing else is populated.
            assertThat(account.creditLimit).isNull()
            assertThat(account.lastKnownBalance).isNull()

            val tx = db.transactionDao().getAll().single()
            assertThat(tx.type).isEqualTo(TransactionType.DEBIT)
            assertThat(tx.amount).isEqualTo(1462.35)
            assertThat(tx.accountId).isEqualTo(account.id)
            assertThat(tx.merchantName).isEqualTo("Green Leaf Organics")
        }

    @Test
    fun `the payment lands on the same card as a credit`() =
        runBlocking {
            repository.insertIncoming("VM-BOBCRD", spend, at(LocalDate.of(2026, 8, 19)))
            repository.insertIncoming("VM-BOBCRD", payment, at(LocalDate.of(2026, 9, 2)))

            val account = db.accountDao().getAll().single()
            assertThat(account.type).isEqualTo(AccountType.CREDIT_CARD)
            val credits = db.transactionDao().getAll().filter { it.type == TransactionType.CREDIT }
            assertThat(credits).hasSize(1)
            assertThat(credits.single().accountId).isEqualTo(account.id)
        }

    @Test
    fun `the statement still derives its bill reminder and never a spend`() =
        runBlocking {
            repository.insertIncoming("VM-BOBCRD", spend, at(LocalDate.of(2026, 8, 19)))
            repository.insertIncoming("VM-BOBCRD", statement, at(LocalDate.of(2026, 8, 28)))

            val reminder = db.reminderDao().getAll().single()
            assertThat(reminder.type).isEqualTo(ReminderType.CREDIT_CARD)
            assertThat(reminder.totalDue).isEqualTo(1462.35)
            assertThat(reminder.minDue).isEqualTo(200.0)
            assertThat(reminder.accountLast4).isEqualTo("5081")
            // The statement's total is an obligation, not a second debit.
            assertThat(db.transactionDao().getAll()).hasSize(1)
            assertThat(
                db
                    .accountDao()
                    .getAll()
                    .single()
                    .type,
            ).isEqualTo(AccountType.CREDIT_CARD)
        }
}
