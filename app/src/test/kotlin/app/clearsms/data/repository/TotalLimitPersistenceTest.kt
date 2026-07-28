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
 * SMS-derived TOTAL credit limits populate accounts.creditLimit — the sole
 * source of the figure now that the manual "Set card limit" entry is gone.
 * No transaction row may ever come out of a limit statement, and a
 * marketing limit-increase OFFER must leave the account untouched.
 */
@RunWith(RobolectricTestRunner::class)
class TotalLimitPersistenceTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var repository: MessageRepositoryImpl

    private val limitChangeBody =
        "Dear Customer, The credit limit for your ICICI Bank Credit Card 4375X9012 " +
            "has been changed from INR 100000 to INR 150000 on 2026-07-01."

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
    fun `a limit-change statement sets the card total without a transaction`() =
        runBlocking {
            repository.insertIncoming("JX-ICICIT", limitChangeBody, 1_000L)

            val account = db.accountDao().getAll().single()
            assertThat(account.accountNumber).isEqualTo("9012")
            assertThat(account.type).isEqualTo(AccountType.CREDIT_CARD)
            assertThat(account.creditLimit).isEqualTo(150000.0)
            assertThat(db.transactionDao().getAll()).isEmpty()
        }

    @Test
    fun `a limit statement refreshes the existing card and keeps its available limit`() =
        runBlocking {
            repository.insertIncoming(
                "VM-ICICIB",
                "INR 2,500.00 spent on ICICI Bank Credit Card XX9012 on 10-Jul-26 at Uber. " +
                    "Avl Limit: INR 97,500.00.",
                1_000L,
            )
            repository.insertIncoming("JX-ICICIT", limitChangeBody, 2_000L)

            val account = db.accountDao().getAll().single()
            assertThat(account.creditLimit).isEqualTo(150000.0)
            assertThat(account.availableLimit).isEqualTo(97500.0)
            assertThat(db.transactionDao().getAll()).hasSize(1)
        }

    @Test
    fun `an older limit statement never clobbers a newer total`() =
        runBlocking {
            repository.insertIncoming("JX-ICICIT", limitChangeBody, 5_000L)
            repository.insertIncoming(
                "JX-ICICIT",
                "Dear Customer, The credit limit for your ICICI Bank Credit Card 4375X9012 " +
                    "has been changed from INR 80000 to INR 100000 on 2026-01-01.",
                1_000L,
            )

            assertThat(
                db
                    .accountDao()
                    .getAll()
                    .single()
                    .creditLimit,
            ).isEqualTo(150000.0)
        }

    @Test
    fun `a limit-increase offer leaves accounts untouched`() =
        runBlocking {
            repository.insertIncoming(
                "VK-SBICRD-S",
                "Congratulations! Your SBI Credit Card 123456 is now eligible for a free of " +
                    "charge Credit Limit increase from Rs. 90,000 to Rs. 150,000. To avail, " +
                    "SMS INCR 1234 to 56767.",
                1_000L,
            )

            assertThat(db.accountDao().getAll()).isEmpty()
        }

    private object NoopDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }
}
