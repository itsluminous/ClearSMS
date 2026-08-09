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
import app.clearsms.domain.model.SubCategory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * End-to-end proof that a balance-ONLY message updates the account without
 * fabricating a transaction: the user-reported HDFC statement shape must set
 * `AccountEntity.lastKnownBalance`/`lastUpdated`, create zero transaction
 * rows, and surface the parsed balance in extractedDataJson (the key the UI
 * and the parsed notification render blue). The plausible-issuer guardrail
 * and the older-message ordering hold for this path too.
 */
@RunWith(RobolectricTestRunner::class)
class BalanceOnlyUpdateTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var repository: MessageRepositoryImpl

    private val userFixture =
        "Available Bal in HDFC Bank A/c XX8709 as on yesterday:27-JUL-26 is INR 40,194.56. " +
            "Cheques are subject to clearing.For updated A/C Bal dial 18002703333."

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
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
    fun `user fixture updates the hdfc account and creates no transaction`() =
        runBlocking {
            val entity = repository.insertIncoming("VM-HDFCBK", userFixture, 2_000L)

            assertThat(db.transactionDao().getAll()).isEmpty()
            val account = db.accountDao().getAll().single()
            assertThat(account.accountNumber).isEqualTo("8709")
            assertThat(account.bankName).isEqualTo("HDFC Bank")
            assertThat(account.lastKnownBalance).isEqualTo(40194.56)
            assertThat(account.lastUpdated).isEqualTo(2_000L)

            assertThat(entity.subCategory).isEqualTo(SubCategory.BANK_ALERT)
            assertThat(entity.extractedDataJson).contains("\"balance\"")
            assertThat(entity.extractedDataJson).contains("8709")
        }

    @Test
    fun `balance statement refreshes an existing account`() =
        runBlocking {
            repository.insertIncoming(
                "VM-HDFCBK",
                "Sent Rs.500.00 From HDFC Bank A/C x8709 To SWIGGY On 12/07/26 Ref 519912345678",
                1_000L,
            )
            repository.insertIncoming("VM-HDFCBK", userFixture, 5_000L)

            val account = db.accountDao().getAll().single()
            assertThat(account.lastKnownBalance).isEqualTo(40194.56)
            assertThat(account.lastUpdated).isEqualTo(5_000L)
            // Still exactly ONE transaction - the debit; the statement added none.
            assertThat(db.transactionDao().getAll()).hasSize(1)
        }

    @Test
    fun `older balance statement never clobbers a newer balance`() =
        runBlocking {
            repository.insertIncoming("VM-HDFCBK", userFixture, 9_000L)
            repository.insertIncoming(
                "VM-HDFCBK",
                "Available Bal in HDFC Bank A/c XX8709 as on yesterday:01-JAN-20 is INR 1.00. " +
                    "Cheques are subject to clearing.",
                1_000L,
            )
            val account = db.accountDao().getAll().single()
            assertThat(account.lastKnownBalance).isEqualTo(40194.56)
            assertThat(account.lastUpdated).isEqualTo(9_000L)
        }

    @Test
    fun `balance mention without a plausible issuer creates no account`() =
        runBlocking {
            repository.insertIncoming("VD-FLPKRT", "Avl Bal: Rs. 250.00 for A/c XX1111", 1_000L)
            assertThat(db.accountDao().getAll()).isEmpty()
            assertThat(db.transactionDao().getAll()).isEmpty()
        }

    @Test
    fun `telecom data balance never touches accounts`() =
        runBlocking {
            repository.insertIncoming(
                "JM-AIRTEL",
                "Your data balance is 1.5 GB. Recharge now to continue enjoying services.",
                1_000L,
            )
            assertThat(db.accountDao().getAll()).isEmpty()
            assertThat(db.transactionDao().getAll()).isEmpty()
        }

    private object NoopDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }
}
