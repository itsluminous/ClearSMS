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
import app.clearsms.domain.parser.CurrencyContext
import app.clearsms.domain.parser.TransactionParser
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
 * End-to-end: the stored transaction carries the currency the message was
 * denominated in, the amount is read under that currency's convention, and
 * the account it lands on is denominated the same way (issue #65). A rupee
 * message keeps storing exactly what it always stored.
 */
@RunWith(RobolectricTestRunner::class)
class CurrencyPersistenceTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var repository: MessageRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        // A device whose SIM says Chile - the reporter's setup.
        val parser = TransactionParser { CurrencyContext(deviceCurrency = "CLP") }
        repository =
            MessageRepositoryImpl(
                database = db,
                categorizer =
                    MessageCategorizer(
                        ruleEngine = RuleEngine(currencyOf = parser::currencyOf),
                        senderIdLookup = SenderIdLookup { null },
                        contactLookup = ContactLookup { false },
                        transactionParser = parser,
                    ),
                bundledRuleLoader = BundledRuleLoader(context, db.ruleDao(), json, NoopDataStore),
                json = json,
                transactionParser = parser,
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a chilean spend is stored as 1000 CLP - never 1 rupee`() =
        runBlocking {
            repository.insertIncoming(
                "BANCOCHILE",
                "Compra por $1.000 en LIDER con tarjeta terminada en 1234 debited. Ref 998877",
                2_000L,
            )
            val tx = db.transactionDao().getAll().single()
            assertThat(tx.amount).isEqualTo(1000.0)
            assertThat(tx.currency).isEqualTo("CLP")
            val message = db.messageDao().getAll().single()
            // The details the notification and the conversation card read
            // carry the currency too.
            assertThat(message.extractedDataJson).contains("\"currency\":\"CLP\"")
        }

    @Test
    fun `a rupee spend stores exactly what it always did`() =
        runBlocking {
            repository.insertIncoming(
                "VM-HDFCBK",
                "UPDATE: INR 13,000.00 debited from HDFC Bank XX8709 on 16-JUL-26. Avl bal:INR 1,07,721.74",
                2_000L,
            )
            val tx = db.transactionDao().getAll().single()
            assertThat(tx.amount).isEqualTo(13000.0)
            assertThat(tx.currency).isEqualTo("INR")
            assertThat(tx.balance).isEqualTo(107721.74)
            val account = db.accountDao().getAll().single()
            assertThat(account.currency).isEqualTo("INR")
            assertThat(account.lastKnownBalance).isEqualTo(107721.74)
            // INR is implied when the details carry no currency key - the
            // encoding every pre-v23 row already uses.
            assertThat(
                db
                    .messageDao()
                    .getAll()
                    .single()
                    .extractedDataJson,
            ).doesNotContain("currency")
        }

    @Test
    fun `a foreign spend on an indian card keeps the card in rupees`() =
        runBlocking {
            repository.insertIncoming(
                "AX-AXISBK",
                "Spent USD 40.95 on Axis Bank Card no. XX5106 at UBER. Avl Limit: INR 2,86,368.50",
                2_000L,
            )
            val tx = db.transactionDao().getAll().single()
            assertThat(tx.amount).isEqualTo(40.95)
            assertThat(tx.currency).isEqualTo("USD")
            // The limit phrase is rupees, so the card's figures stay rupees.
            val account = db.accountDao().getAll().single()
            assertThat(account.currency).isEqualTo("INR")
            assertThat(account.availableLimit).isEqualTo(286368.5)
        }

    private object NoopDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }
}
