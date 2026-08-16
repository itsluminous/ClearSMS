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
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.TransactionType
import app.clearsms.domain.parser.SenderNameResolver
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
 * KFintech NPS (sender KFNCRA) attribution. KFintech is the OTHER CRA (next
 * to Protean/NSDL) but the PRAN namespace is one - a subscriber has ONE
 * PRAN - so both CRAs' messages land on the SAME unified "NPS" institution,
 * keyed by the PRAN tail, and never on a bank account sharing the tail.
 *
 * The fund-confirmation / units-credited PAIR announces the SAME
 * contribution twice. The FUND CONFIRMATION is the credit of record - it
 * mirrors EPFO's "Contribution ... has been received" (money HAS left the
 * user and reached the scheme) and it is the only shape carrying the PRAN
 * tail, so it keys the account with its real identity. The units-credited
 * follow-up must NOT become a second credit; its valuation refreshes the
 * account balance instead (tail-less: allowed to UPDATE the scheme's sole
 * account, never to create one).
 */
@RunWith(RobolectricTestRunner::class)
class KfinNpsIngestionTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var repository: MessageRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true }

    private object NoopStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }

    private val fundConfirmation =
        "Dear Subscriber, We have received fund confirmation of Rs.50000.00 for " +
            "investment in PRAN XXXX7287 Tier-I. Units will be credited by end of the day. - KFNCRA"
    private val unitsCredited =
        "Your contribution of Rs.50,000.00 has been credited to your NPS Tier-I a/c " +
            "on 5-Jan-26 and valuation of your Tier-I a/c is Rs.4,99,291.32 - KFNCRA"
    private val valuation =
        "Value of investments in your PRAN XX7287 Tier -I account as on 30/06/2026 " +
            "is Rs. 490816.97 respectively. - KFNCRA"

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

    @Test
    fun `fund confirmation ingests as an INVESTMENT CREDIT on the NPS account keyed by the PRAN tail`() =
        runBlocking {
            val entity = repository.insertIncoming("VM-KFNCRA-S", fundConfirmation, 1_000L)
            assertThat(entity.category).isEqualTo(Category.IMPORTANT)
            val tx = db.transactionDao().getAll().single()
            assertThat(tx.type).isEqualTo(TransactionType.CREDIT)
            assertThat(tx.amount).isEqualTo(50000.0)
            assertThat(tx.accountNumber).isEqualTo("7287")
            assertThat(tx.bankName).isEqualTo("NPS")
            val account = db.accountDao().getAll().single()
            assertThat(account.bankName).isEqualTo("NPS")
            assertThat(account.accountNumber).isEqualTo("7287")
        }

    @Test
    fun `the fund-confirmation + units-credited PAIR yields exactly ONE credit`() =
        runBlocking {
            repository.insertIncoming("VM-KFNCRA-S", fundConfirmation, 1_000L)
            repository.insertIncoming("VM-KFNCRA-S", unitsCredited, 8 * 60 * 60 * 1000L)
            val txs = db.transactionDao().getAll()
            assertThat(txs).hasSize(1)
            assertThat(txs.single().type).isEqualTo(TransactionType.CREDIT)
            assertThat(txs.single().amount).isEqualTo(50000.0)
        }

    @Test
    fun `units-credited updates the sole NPS account's valuation without a transaction of its own`() =
        runBlocking {
            repository.insertIncoming("VM-KFNCRA-S", fundConfirmation, 1_000L)
            repository.insertIncoming("VM-KFNCRA-S", unitsCredited, 8 * 60 * 60 * 1000L)
            val account = db.accountDao().getAll().single()
            assertThat(account.accountNumber).isEqualTo("7287")
            assertThat(account.lastKnownBalance).isEqualTo(499291.32)
        }

    @Test
    fun `a tail-less units-credited alone never CREATES an account`() =
        runBlocking {
            repository.insertIncoming("VM-KFNCRA-S", unitsCredited, 1_000L)
            assertThat(db.transactionDao().getAll()).isEmpty()
            assertThat(db.accountDao().getAll()).isEmpty()
        }

    @Test
    fun `valuation statement is a balance update only - no transaction, as-on date ignored`() =
        runBlocking {
            repository.insertIncoming("VM-KFNCRA-S", valuation, 1_000L)
            assertThat(db.transactionDao().getAll()).isEmpty()
            val account = db.accountDao().getAll().single()
            assertThat(account.accountNumber).isEqualTo("7287")
            assertThat(account.bankName).isEqualTo("NPS")
            assertThat(account.lastKnownBalance).isEqualTo(490816.97)
        }

    @Test
    fun `PRAN tail variants XXXX7287 and XX7287 unify to ONE account`() =
        runBlocking {
            repository.insertIncoming("VM-KFNCRA-S", fundConfirmation, 1_000L)
            repository.insertIncoming("VM-KFNCRA-S", valuation, 2_000L)
            val account = db.accountDao().getAll().single()
            assertThat(account.accountNumber).isEqualTo("7287")
            assertThat(account.lastKnownBalance).isEqualTo(490816.97)
        }

    @Test
    fun `Protean- and KFin-reported PRANs with the same tail unify on the ONE NPS institution`() =
        runBlocking {
            repository.insertIncoming(
                "VA-PTNNPS-S",
                "PRAN XX7287: Units for (MAY-2026) contribution of Rs.37,412.00 credited " +
                    "with NAV of 08/06/26 -Protean",
                1_000L,
            )
            repository.insertIncoming("VM-KFNCRA-S", fundConfirmation, 2_000L)
            val account = db.accountDao().getAll().single()
            assertThat(account.bankName).isEqualTo("NPS")
            assertThat(account.accountNumber).isEqualTo("7287")
            assertThat(db.transactionDao().getAll()).hasSize(2)
        }

    @Test
    fun `an NPS account never collides with a bank account sharing the 7287 tail`() =
        runBlocking {
            repository.insertIncoming(
                "JM-HDFCBK-S",
                "Update! INR 55,000.00 deposited in HDFC Bank A/c XX7287 on 30-JUL-26 for ACH C- SALARY." +
                    "Avl bal INR 60,000.00",
                1_000L,
            )
            repository.insertIncoming("VM-KFNCRA-S", fundConfirmation, 2_000L)
            val accounts = db.accountDao().getAll()
            assertThat(accounts.map { it.bankName }).containsExactly("HDFC Bank", "NPS")
            val nps = db.transactionDao().getAll().single { it.bankName == "NPS" }
            assertThat(nps.accountId).isEqualTo(accounts.single { it.bankName == "NPS" }.id)
        }

    @Test
    fun `KFNCRA resolves to the unified NPS institution and the KFintech NPS brand`() {
        assertThat(SenderNameResolver.bankNameFor("VM-KFNCRA-S")).isEqualTo("NPS")
        assertThat(SenderNameResolver.canonicalize("Protean NPS")).isEqualTo("NPS")
        assertThat(SenderNameResolver.isPlausibleIssuer("NPS")).isTrue()
        assertThat(SenderNameResolver.isRetirementIssuer("NPS")).isTrue()
    }
}
