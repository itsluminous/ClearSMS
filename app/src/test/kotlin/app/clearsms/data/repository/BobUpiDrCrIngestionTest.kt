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
import app.clearsms.domain.model.SenderInfo
import app.clearsms.domain.model.SubCategory
import app.clearsms.domain.model.TransactionType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * GitHub #166, end to end: a Bank of Baroda UPI alert in the abbreviated
 * "Rs.X Dr. from A/C ... and Cr. to <vpa>" form must land in Finance as a
 * DEBIT on the Bank of Baroda account - the same row the details card
 * describes - instead of only refreshing the account's balance.
 *
 * The sender directory is mirrored as the community one files BOB: IMPORTANT
 * with no sub-category. That is exactly the verdict that hid the bug: with the
 * rules reverted, the message is IMPORTANT, its details show the balance and
 * account (the heuristic balance-statement parser reads "AvlBal:Rs..."), the
 * account's balance is upserted, and NO transaction row exists.
 *
 * Every value is SYNTHETIC: the VPA, tail, reference and balance are invented.
 */
@RunWith(RobolectricTestRunner::class)
class BobUpiDrCrIngestionTest {
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
                        senderIdLookup =
                            SenderIdLookup { sender ->
                                if (sender.uppercase().contains("BOBSMS")) SenderInfo("BOBSMS", Category.IMPORTANT, null) else null
                            },
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

    private val bobUpiDebit =
        "Rs.78.00 Dr. from A/C XXXXXX4421 and Cr. to shopkeeper.demo@ptys. Ref:512345678901. " +
            "AvlBal:Rs1234.56(2026:11:11 10:28:46). Not you? Call 18005700/5000-BOB"

    private val bobUpiCredit =
        "Rs.250.00 Cr. to A/C XXXXXX4421 and Dr. from friend.demo@okaxis. Ref:512345678902. " +
            "AvlBal:Rs1484.56(2026:11:12 09:01:02). Not you? Call 18005700/5000-BOB"

    private fun details(raw: String?): Map<String, String> =
        json.parseToJsonElement(checkNotNull(raw)).jsonObject.mapValues { it.value.jsonPrimitive.content }

    @Test
    fun `bob upi debit lands in finance as a debit on the bank of baroda account`() =
        runBlocking {
            val entity = repository.insertIncoming("VK-BOBSMS-S", bobUpiDebit, 1_000L)
            assertThat(entity.category).isEqualTo(Category.IMPORTANT)
            assertThat(entity.subCategory).isEqualTo(SubCategory.TRANSACTION)

            val tx = db.transactionDao().getAll().single()
            assertThat(tx.type).isEqualTo(TransactionType.DEBIT)
            assertThat(tx.amount).isEqualTo(78.0)
            assertThat(tx.accountNumber).isEqualTo("4421")
            assertThat(tx.bankName).isEqualTo("Bank of Baroda")
            assertThat(tx.merchantName).isEqualTo("shopkeeper.demo@ptys")
            assertThat(tx.balance).isEqualTo(1234.56)
            assertThat(tx.rawSmsId).isEqualTo(entity.id)

            val account = db.accountDao().getAll().single()
            assertThat(account.bankName).isEqualTo("Bank of Baroda")
            assertThat(account.accountNumber).isEqualTo("4421")
            assertThat(tx.accountId).isEqualTo(account.id)
            assertThat(account.lastKnownBalance).isEqualTo(1234.56)

            // The details card and Finance describe the SAME movement.
            val shown = details(entity.extractedDataJson)
            // Rule captures win over parser output for shared keys, as matched.
            assertThat(shown["amount"]).isEqualTo("78.00")
            assertThat(shown["type"]).isEqualTo("debit")
            assertThat(shown["account_last4"]).isEqualTo("4421")
            assertThat(shown["bank"]).isEqualTo("Bank of Baroda")
            assertThat(shown["merchant"]).isEqualTo("shopkeeper.demo@ptys")
        }

    @Test
    fun `bob upi credit lands as a credit from the payer vpa`() =
        runBlocking {
            repository.insertIncoming("VK-BOBSMS-S", bobUpiCredit, 2_000L)
            val tx = db.transactionDao().getAll().single()
            assertThat(tx.type).isEqualTo(TransactionType.CREDIT)
            assertThat(tx.amount).isEqualTo(250.0)
            assertThat(tx.merchantName).isEqualTo("friend.demo@okaxis")
            assertThat(
                db
                    .accountDao()
                    .getAll()
                    .single()
                    .lastKnownBalance,
            ).isEqualTo(1484.56)
        }
}
