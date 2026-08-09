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
 * A recharge or bill payment has no third-party merchant - the biller IS the
 * sender - so the transaction is titled with the resolved sender brand
 * ("Airtel") instead of a generic phrase like "Prepaid Recharge".
 */
@RunWith(RobolectricTestRunner::class)
class RechargeBrandTitleTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var repo: MessageRepositoryImpl

    private object NoopDataStore : DataStore<Preferences> {
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
        val json = Json { ignoreUnknownKeys = true }
        repo =
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
    fun tearDown() = db.close()

    private val rechargeBody =
        "Hi, Your Prepaid recharge of Rs. 1198.0 is success against Order Id 7481038541423177728. " +
            "Please keep the Order ID for future reference."

    private suspend fun titleFor(
        sender: String,
        body: String,
    ): String? {
        repo.insertIncoming(sender, body, System.currentTimeMillis())
        return db
            .transactionDao()
            .getAll()
            .lastOrNull()
            ?.merchantName
    }

    @Test
    fun `airtel recharge is titled Airtel, not a generic phrase`() =
        runBlocking<Unit> {
            val title = titleFor("VM-AIRTEL", rechargeBody)
            assertThat(title).isNotNull()
            assertThat(title).ignoringCase().contains("airtel")
            assertThat(title).ignoringCase().doesNotContain("prepaid recharge")
        }

    @Test
    fun `the same recharge from Jio is titled with the Jio brand`() =
        runBlocking<Unit> {
            val title = titleFor("VM-JIO", rechargeBody)
            assertThat(title).isNotNull()
            assertThat(title).ignoringCase().doesNotContain("prepaid recharge")
        }

    @Test
    fun `a recharge naming a real merchant keeps that merchant`() =
        runBlocking<Unit> {
            val body =
                "Rs.500.00 debited from a/c XX1234 on 20-07-26 to PAYTM RECHARGE. " +
                    "Ref No 998877665544. Avl Bal Rs.4,500.00 - HDFC Bank."
            val title = titleFor("VM-HDFCBK", body)
            assertThat(title).isNotNull()
            assertThat(title).ignoringCase().contains("paytm")
        }

    @Test
    fun `no bundled generic recharge merchant literal remains in the rules`() {
        val asset =
            ApplicationProvider
                .getApplicationContext<Context>()
                .assets
                .open("default_rules.json")
                .bufferedReader()
                .use { it.readText() }
        assertThat(asset).doesNotContain("\"Prepaid Recharge\"")
    }
}
