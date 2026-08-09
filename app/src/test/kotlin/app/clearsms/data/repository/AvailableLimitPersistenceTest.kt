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
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * End-to-end proof that a card-spend SMS carrying "Avl Limit" lands the
 * limit on the account row's dedicated `availableLimit` column - never on
 * `lastKnownBalance` (the semantics differ) - with the usual timestamp
 * ordering: older messages never clobber a newer limit, and a newer message
 * without a limit keeps the existing one.
 */
@RunWith(RobolectricTestRunner::class)
class AvailableLimitPersistenceTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var repository: MessageRepositoryImpl

    /** The user-reported ICICI card fixture. */
    private val iciciFixture =
        "INR 2.00 spent using ICICI Bank Card XX4001 on 27-Jul-26 on AMAZON. " +
            "Avl Limit: INR 2,87,185.45. If not you, call 1800 2662/SMS BLOCK 4001 to 9215676766."

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
    fun `icici fixture stores the available limit on the card account`() =
        runBlocking {
            repository.insertIncoming("VM-ICICIB", iciciFixture, 2_000L)

            val account = db.accountDao().getAll().single()
            assertThat(account.type).isEqualTo(AccountType.CREDIT_CARD)
            assertThat(account.accountNumber).isEqualTo("4001")
            assertThat(account.availableLimit).isEqualTo(287185.45)
            // NEVER overloaded onto the balance: a card's headroom is not a balance.
            assertThat(account.lastKnownBalance).isNull()
        }

    @Test
    fun `older message never clobbers a newer available limit`() =
        runBlocking {
            repository.insertIncoming("VM-ICICIB", iciciFixture, 9_000L)
            repository.insertIncoming(
                "VM-ICICIB",
                "INR 500.00 spent using ICICI Bank Card XX4001 on 01-Jan-20 on SWIGGY. Avl Limit: INR 1.00.",
                1_000L,
            )

            val account = db.accountDao().getAll().single()
            assertThat(account.availableLimit).isEqualTo(287185.45)
            assertThat(account.lastUpdated).isEqualTo(9_000L)
        }

    @Test
    fun `newer message without a limit keeps the existing one`() =
        runBlocking {
            repository.insertIncoming("VM-ICICIB", iciciFixture, 2_000L)
            repository.insertIncoming(
                "VM-ICICIB",
                "INR 99.00 spent using ICICI Bank Card XX4001 on 28-Jul-26 on NETFLIX.",
                5_000L,
            )

            val account = db.accountDao().getAll().single()
            assertThat(account.availableLimit).isEqualTo(287185.45)
            assertThat(account.lastUpdated).isEqualTo(5_000L)
        }

    private object NoopDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }
}
