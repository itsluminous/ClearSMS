package app.clearsms.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.AccountEntity
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
 * "Sort inbox again" sweeps ORPHANED accounts: rule/parser evolution can
 * stop a message from deriving its transaction, leaving an account row in
 * Finance with zero transactions and nothing refreshing it. After the full
 * pass, accounts the run never touched that own no transaction are deleted;
 * everything a message still re-derives (transactions, balance-only
 * statements, limit statements) survives. The sweep runs ONLY on the
 * re-sort - imports (initial or catch-up) only ever ADD derived rows and
 * cannot strand an account, so they never sweep.
 */
@RunWith(RobolectricTestRunner::class)
class RecategorizeOrphanSweepTest {
    private lateinit var db: app.clearsms.data.db.ClearSmsDatabase
    private lateinit var repository: MessageRepositoryImpl

    /** Debit that derives a transaction AND its HDFC account. */
    private val debitBody =
        "UPDATE: INR 13,000.00 debited from HDFC Bank XX8709 on 16-JUL-26. " +
            "Info: XXXXXXXXXX6894- RD Installment-Jul 2026. Avl bal:INR 1,07,721.74"

    /** Balance-ONLY statement: creates/refreshes an account, no transaction. */
    private val balanceBody =
        "Available Bal in HDFC Bank A/c XX4321 as on yesterday:27-JUL-26 is INR 40,194.56. " +
            "Cheques are subject to clearing.For updated A/C Bal dial 18002703333."

    /** Retirement valuation - the balance-only NPS account shape. */
    private val npsValuationBody =
        "Your contribution of Rs.9,000 is invested on 5-Jan-26 and valuation of " +
            "your Tier-I a/c is Rs.4,99,291.32 - KFNCRA"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, app.clearsms.data.db.ClearSmsDatabase::class.java)
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

    /** Account row no message backs - the shape rule evolution leaves behind. */
    private suspend fun insertOrphanAccount(): Long =
        db.accountDao().insert(
            AccountEntity(
                accountNumber = "9999",
                bankName = "Ghost Bank",
                type = AccountType.SAVINGS,
                lastUpdated = 1_000L,
            ),
        )

    @Test
    fun `re-sort deletes an untouched account owning zero transactions`() =
        runBlocking {
            repository.insertIncoming("VM-HDFCBK", debitBody, 1_000L)
            val orphanId = insertOrphanAccount()

            repository.recategorizeAll { _, _ -> }

            assertThat(db.accountDao().findById(orphanId)).isNull()
            // The genuinely backed account survives with its transaction.
            val kept = db.accountDao().getAll().single()
            assertThat(kept.accountNumber).isEqualTo("8709")
            assertThat(db.transactionDao().getAll()).hasSize(1)
        }

    @Test
    fun `balance-statement-only accounts survive the sweep - the balance upsert is a touch`() =
        runBlocking {
            repository.insertIncoming("VM-HDFCBK", balanceBody, 1_000L)
            // No transaction exists for this account - only the statement.
            assertThat(db.transactionDao().getAll()).isEmpty()

            repository.recategorizeAll { _, _ -> }

            val kept = db.accountDao().getAll().single()
            assertThat(kept.accountNumber).isEqualTo("4321")
            assertThat(kept.lastKnownBalance).isEqualTo(40194.56)
        }

    @Test
    fun `retirement valuation account survives the sweep with its balance`() =
        runBlocking {
            // PRAN-tailed contribution creates the account; the valuation is
            // the balance-only refresher.
            repository.insertIncoming(
                "VM-NSDLNP",
                "PRAN XX8227: Units for (APR-2026) contribution of Rs.44,236.00 credited " +
                    "with NAV of 07/05/26 -Protean",
                1_000L,
            )
            repository.insertIncoming("VM-KFNCRA", npsValuationBody, 2_000L)

            repository.recategorizeAll { _, _ -> }

            assertThat(db.accountDao().getAll()).isNotEmpty()
        }

    @Test
    fun `user-set card limit on a backed account survives the sweep`() =
        runBlocking {
            repository.insertIncoming("VM-HDFCBK", debitBody, 1_000L)
            val account = db.accountDao().getAll().single()
            db.accountDao().update(account.copy(creditLimit = 250_000.0))
            val orphanId = insertOrphanAccount()

            repository.recategorizeAll { _, _ -> }

            val kept = db.accountDao().getAll().single()
            assertThat(kept.id).isEqualTo(account.id)
            assertThat(kept.creditLimit).isEqualTo(250_000.0)
            assertThat(db.accountDao().findById(orphanId)).isNull()
        }

    @Test
    fun `a user-set limit on a genuinely unbacked account dies with it - nothing re-derives the row`() =
        runBlocking {
            val orphanId = insertOrphanAccount()
            db.accountDao().update(db.accountDao().findById(orphanId)!!.copy(creditLimit = 100_000.0))

            repository.recategorizeAll { _, _ -> }

            assertThat(db.accountDao().findById(orphanId)).isNull()
        }

    @Test
    fun `live ingestion and imports never sweep - only the full re-sort can strand accounts`() =
        runBlocking {
            val orphanId = insertOrphanAccount()

            // Live ingestion (shared with catch-up imports via persistDerived)
            // only ADDS derived rows: the unrelated zero-transaction account
            // must survive it untouched.
            repository.insertIncoming("VM-HDFCBK", debitBody, 1_000L)

            assertThat(db.accountDao().findById(orphanId)).isNotNull()
        }

    @Test
    fun `repeated re-sorts keep the swept state stable`() =
        runBlocking {
            repository.insertIncoming("VM-HDFCBK", debitBody, 1_000L)
            insertOrphanAccount()

            repository.recategorizeAll { _, _ -> }
            repository.recategorizeAll { _, _ -> }

            assertThat(db.accountDao().getAll()).hasSize(1)
            assertThat(db.transactionDao().getAll()).hasSize(1)
        }

    private object NoopDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }
}
