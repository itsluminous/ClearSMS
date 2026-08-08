package app.clearsms.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.AccountEntity
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.rules.BundledRuleLoader
import app.clearsms.data.rules.RuleEngine
import app.clearsms.domain.categorizer.ContactLookup
import app.clearsms.domain.categorizer.MessageCategorizer
import app.clearsms.domain.categorizer.SenderIdLookup
import app.clearsms.domain.model.AccountType
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
 * End-to-end Pluxee meal-wallet recognition: the digit-less "successfully
 * credited with Rs.X towards Meal Wallet" load parses as a CREDIT with the
 * loaded amount (never the trailing balance) and lands on ONE issuer-keyed
 * Pluxee WALLET account; the established meal-card debit keeps parsing; and
 * failed credits never become transactions.
 */
@RunWith(RobolectricTestRunner::class)
class PluxeeWalletIngestionTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var repository: MessageRepositoryImpl

    private val pluxeeCredit =
        "Your Pluxee Card has been successfully credited with Rs.2200 towards  Meal Wallet " +
            "on Sat Aug 08 2026 01:20:51. Your current Meal Wallet balance is Rs.2200.00."

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
    fun `digit-less meal wallet load becomes a credit on an issuer-keyed pluxee wallet`() =
        runBlocking {
            repository.insertIncoming("VD-PLUXEE-S", pluxeeCredit, 1_000L)

            val tx = db.transactionDao().getAll().single()
            assertThat(tx.type).isEqualTo(TransactionType.CREDIT)
            assertThat(tx.amount).isEqualTo(2200.0)
            assertThat(tx.bankName).isEqualTo("Pluxee")

            val account = db.accountDao().getAll().single()
            assertThat(account.bankName).isEqualTo("Pluxee")
            assertThat(account.type).isEqualTo(AccountType.WALLET)
            assertThat(account.accountNumber).isEqualTo("PLUXEE")
            assertThat(account.lastKnownBalance).isEqualTo(2200.0)
            assertThat(tx.accountId).isEqualTo(account.id)
        }

    @Test
    fun `loaded amount wins over the trailing balance when they differ`() =
        runBlocking {
            repository.insertIncoming(
                "VD-PLUXEE-S",
                "Your Pluxee Card has been successfully credited with Rs.500 towards Meal Wallet " +
                    "on Fri Aug 07 2026 09:00:00. Your current Meal Wallet balance is Rs.1234.56.",
                1_000L,
            )
            val tx = db.transactionDao().getAll().single()
            assertThat(tx.amount).isEqualTo(500.0)
            assertThat(tx.type).isEqualTo(TransactionType.CREDIT)
            assertThat(
                db
                    .accountDao()
                    .getAll()
                    .single()
                    .lastKnownBalance,
            ).isEqualTo(1234.56)
        }

    @Test
    fun `a digit-less credit attaches to an existing sole pluxee wallet instead of inventing a second`() =
        runBlocking {
            // A digit-keyed Pluxee wallet already exists (the meal-card debit
            // shape quotes a last-4).
            db.accountDao().insert(
                AccountEntity(
                    accountNumber = "5919",
                    bankName = "Pluxee",
                    type = AccountType.WALLET,
                    lastKnownBalance = null,
                    lastUpdated = 500L,
                ),
            )
            repository.insertIncoming("VD-PLUXEE-S", pluxeeCredit, 1_000L)

            val accounts = db.accountDao().getAll()
            assertThat(accounts).hasSize(1)
            assertThat(accounts.single().accountNumber).isEqualTo("5919")
            assertThat(
                db
                    .transactionDao()
                    .getAll()
                    .single()
                    .accountId,
            ).isEqualTo(accounts.single().id)
        }

    @Test
    fun `two digit-less credits share ONE pluxee wallet`() =
        runBlocking {
            repository.insertIncoming("VD-PLUXEE-S", pluxeeCredit, 1_000L)
            repository.insertIncoming(
                "VM-PLUXEE-S",
                "Your Pluxee Card has been successfully credited with Rs.150 towards Meal Wallet " +
                    "on Mon Aug 10 2026 08:00:00. Your current Meal Wallet balance is Rs.2350.00.",
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
    fun `existing pluxee meal card debit keeps parsing as a debit`() =
        runBlocking {
            repository.insertIncoming(
                "VD-PLUXEE-S",
                "Rs.180.00 was spent from your Meal A/c 5919 at COMPASS IND on 07-08-2026. " +
                    "Meal A/c bal is Rs.2020.00.",
                1_000L,
            )
            val tx = db.transactionDao().getAll().single()
            assertThat(tx.type).isEqualTo(TransactionType.DEBIT)
            assertThat(tx.amount).isEqualTo(180.0)
            assertThat(tx.accountNumber).isEqualTo("5919")
        }

    @Test
    fun `failed pluxee credit never becomes a transaction`() =
        runBlocking {
            repository.insertIncoming(
                "VD-PLUXEE-S",
                "Your Pluxee Card credit of Rs.2200 towards Meal Wallet could not be processed. " +
                    "Please contact your employer.",
                1_000L,
            )
            assertThat(db.transactionDao().getAll()).isEmpty()
            assertThat(db.accountDao().getAll()).isEmpty()
        }

    private object NoopDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }
}
