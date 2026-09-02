package app.clearsms.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.rules.BundledRuleLoader
import app.clearsms.data.rules.RuleAction
import app.clearsms.data.rules.RuleDefinition
import app.clearsms.data.rules.RuleEngine
import app.clearsms.data.rules.RuleMatch
import app.clearsms.data.rules.RuleSources
import app.clearsms.data.rules.toEntity
import app.clearsms.domain.categorizer.ContactLookup
import app.clearsms.domain.categorizer.MessageCategorizer
import app.clearsms.domain.categorizer.SenderIdLookup
import app.clearsms.domain.model.Category
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
 * The reported defect: changing a message's category from the inbox adds a
 * rule, but the message stayed where it was until the user ran Settings → Sort
 * inbox again. A sender-bound rule is now applied to that sender's existing
 * messages immediately, and only those - re-sorting the whole inbox for one
 * sender would cost minutes on a phone.
 */
@RunWith(RobolectricTestRunner::class)
class RecategorizeSenderTest {
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
                        senderIdLookup = SenderIdLookup { null },
                        contactLookup = ContactLookup { false },
                    ),
                bundledRuleLoader = BundledRuleLoader(context, db.ruleDao(), json, NoopStore),
                json = json,
            )
    }

    @After
    fun tearDown() = db.close()

    /** The wizard's shape for "this sender's messages are OTPs". */
    private fun otpRuleFor(senderCore: String) =
        RuleDefinition(
            id = "user-otp-$senderCore",
            name = "Treat $senderCore as OTP",
            priority = 900,
            match = RuleMatch(senderPattern = "(?i)$senderCore", bodyPattern = null),
            action = RuleAction(category = "otp", subCategory = "otp"),
        )

    @Test
    fun `a sender-bound rule reaches that sender's existing messages`() =
        runBlocking {
            val entity = repository.insertIncoming("TX-NEWCO", "Your NEWCO service request has been noted. Ref 4523", 1_000L)
            assertThat(entity.category).isNotEqualTo(Category.OTP)

            db.ruleDao().insert(otpRuleFor("NEWCO").toEntity(json, RuleSources.USER))
            val processed = repository.recategorizeSenderCore("NEWCO")

            assertThat(processed).isEqualTo(1)
            assertThat(db.messageDao().getById(entity.id)!!.category).isEqualTo(Category.OTP)
        }

    @Test
    fun `other senders are left untouched`() =
        runBlocking {
            val target = repository.insertIncoming("TX-NEWCO", "Your NEWCO service request has been noted. Ref 4523", 1_000L)
            val other = repository.insertIncoming("TX-OTHERCO", "Your OTHERCO service request has been noted. Ref 9911", 2_000L)
            val otherCategoryBefore = db.messageDao().getById(other.id)!!.category

            db.ruleDao().insert(otpRuleFor("NEWCO").toEntity(json, RuleSources.USER))
            val processed = repository.recategorizeSenderCore("NEWCO")

            assertThat(processed).isEqualTo(1)
            assertThat(db.messageDao().getById(target.id)!!.category).isEqualTo(Category.OTP)
            assertThat(db.messageDao().getById(other.id)!!.category).isEqualTo(otherCategoryBefore)
        }

    @Test
    fun `every message from the sender is re-sorted, whatever the TRAI prefix`() =
        runBlocking {
            val a = repository.insertIncoming("VM-NEWCO", "NEWCO service update one. Ref 1111", 1_000L)
            val b = repository.insertIncoming("AX-NEWCO", "NEWCO service update two. Ref 2222", 2_000L)

            db.ruleDao().insert(otpRuleFor("NEWCO").toEntity(json, RuleSources.USER))
            val processed = repository.recategorizeSenderCore("NEWCO")

            assertThat(processed).isEqualTo(2)
            assertThat(db.messageDao().getById(a.id)!!.category).isEqualTo(Category.OTP)
            assertThat(db.messageDao().getById(b.id)!!.category).isEqualTo(Category.OTP)
        }

    @Test
    fun `binned messages are not resurrected with derived rows`() =
        runBlocking {
            // A blocked or keyword-binned message must not gain finance rows
            // from a re-sort it was never meant to be part of.
            val entity =
                repository.insertIncoming(
                    "TX-NEWCO",
                    "Rs.500 debited from A/c XX1234. Avl bal Rs.900",
                    1_000L,
                )
            val staged = repository.stageDeleteMessages(listOf(entity.id))
            repository.commitStagedDelete(staged, toBin = true)
            db.transactionDao().deleteByRawSmsId(entity.id)

            val processed = repository.recategorizeSenderCore("NEWCO")

            assertThat(processed).isEqualTo(0)
            assertThat(db.transactionDao().getAll()).isEmpty()
        }

    @Test
    fun `an unknown sender core is a no-op rather than a full re-sort`() =
        runBlocking {
            repository.insertIncoming("TX-NEWCO", "Your NEWCO service request has been noted. Ref 4523", 1_000L)

            assertThat(repository.recategorizeSenderCore("NOSUCHSENDER")).isEqualTo(0)
            assertThat(repository.recategorizeSenderCore("")).isEqualTo(0)
        }
}
