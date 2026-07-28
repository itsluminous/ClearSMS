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
 * "Sort inbox again" REFRESHES derived transaction rows in place: stale
 * titles from older parser/rule versions are rewritten, repeated runs never
 * duplicate rows or drift totals, user notes survive onto the re-derived
 * rows, accounts (and their user-set card limits) are never deleted, and
 * orphaned transaction rows from messages that no longer derive anything
 * disappear.
 */
@RunWith(RobolectricTestRunner::class)
class RecategorizeTransactionRefreshTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var repository: MessageRepositoryImpl

    private val rdBody =
        "UPDATE: INR 13,000.00 debited from HDFC Bank XX8709 on 16-JUL-26. " +
            "Info: XXXXXXXXXX6894- RD Installment-Jul 2026. Avl bal:INR 1,07,721.74"

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
    fun `re-sort rewrites a stale transaction title in place`() =
        runBlocking {
            repository.insertIncoming("VM-HDFCBK", rdBody, 1_000L)
            // Simulate a row derived by an older, buggier build.
            val derived = db.transactionDao().getAll().single()
            db.transactionDao().insert(derived.copy(merchantName = "XXXXXXXXXX6894- RD Installment-Jul 2026"))

            repository.recategorizeAll { _, _ -> }

            val refreshed = db.transactionDao().getAll().single()
            assertThat(refreshed.merchantName).isEqualTo("RD Installment")
            assertThat(refreshed.amount).isEqualTo(13000.0)
        }

    @Test
    fun `two consecutive re-sorts never duplicate rows or drift totals`() =
        runBlocking {
            repository.insertIncoming("VM-HDFCBK", rdBody, 1_000L)
            repository.insertIncoming(
                "VM-NSDLNP",
                "PRAN XX8227: Units for (APR-2026) contribution of Rs.44,236.00 credited " +
                    "with NAV of 07/05/26 -Protean",
                2_000L,
            )
            val totalBefore = db.transactionDao().getAll().sumOf { it.amount }

            repository.recategorizeAll { _, _ -> }
            repository.recategorizeAll { _, _ -> }

            val transactions = db.transactionDao().getAll()
            assertThat(transactions).hasSize(2)
            assertThat(transactions.map { it.rawSmsId }.distinct()).hasSize(2)
            assertThat(transactions.sumOf { it.amount }).isEqualTo(totalBefore)
            assertThat(db.accountDao().getAll()).hasSize(2)
        }

    @Test
    fun `user note survives the transaction refresh`() =
        runBlocking {
            repository.insertIncoming("VM-HDFCBK", rdBody, 1_000L)
            val derived = db.transactionDao().getAll().single()
            db.transactionDao().setNote(derived.id, "monthly deposit")

            repository.recategorizeAll { _, _ -> }
            repository.recategorizeAll { _, _ -> }

            assertThat(
                db
                    .transactionDao()
                    .getAll()
                    .single()
                    .note,
            ).isEqualTo("monthly deposit")
        }

    @Test
    fun `user-set card limit survives the refresh because accounts are never deleted`() =
        runBlocking {
            repository.insertIncoming("VM-HDFCBK", rdBody, 1_000L)
            val account = db.accountDao().getAll().single()
            db.accountDao().update(account.copy(creditLimit = 250_000.0))

            repository.recategorizeAll { _, _ -> }

            val kept = db.accountDao().getAll().single()
            assertThat(kept.creditLimit).isEqualTo(250_000.0)
            assertThat(kept.id).isEqualTo(account.id)
        }

    @Test
    fun `stale transaction row of a message that derives nothing is removed`() =
        runBlocking {
            val personal = repository.insertIncoming("Ravi", "See you at 6?", 1_000L)
            // A bogus row left behind by an older build.
            db.transactionDao().insert(
                TransactionEntity(
                    amount = 99.0,
                    type = TransactionType.DEBIT,
                    merchantName = "Ghost",
                    accountNumber = "",
                    bankName = "",
                    timestamp = 1_000L,
                    rawSmsId = personal.id,
                ),
            )

            repository.recategorizeAll { _, _ -> }

            assertThat(db.transactionDao().getAll()).isEmpty()
        }

    private object NoopDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }
}
