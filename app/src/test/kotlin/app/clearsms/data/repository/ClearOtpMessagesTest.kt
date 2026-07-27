package app.clearsms.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.rules.BundledRuleLoader
import app.clearsms.data.rules.RuleEngine
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

/** The manual "Clear older OTPs" path: count first, scoped delete second. */
@RunWith(RobolectricTestRunner::class)
class ClearOtpMessagesTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var repository: MessageRepositoryImpl
    private val deletedFromProvider = mutableListOf<List<Long>>()

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
                systemSmsDeleter = { ids ->
                    deletedFromProvider += ids
                    ids.size
                },
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun message(
        id: Long,
        category: Category,
        timestamp: Long,
        extractedOtp: String? = null,
        systemSmsId: Long? = null,
    ) = MessageEntity(
        id = id,
        threadId = id,
        sender = "sender-$id",
        normalizedSender = "sender-$id",
        body = "body $id",
        timestamp = timestamp,
        category = category,
        extractedOtp = extractedOtp,
        systemSmsId = systemSmsId,
    )

    @Test
    fun `count before delete matches only otp messages older than the cutoff`() =
        runBlocking<Unit> {
            db.messageDao().insertAll(
                listOf(
                    message(1, Category.OTP, timestamp = 100),
                    message(2, Category.OTP, timestamp = 200),
                    message(3, Category.OTP, timestamp = 500),
                    message(4, Category.PERSONAL, timestamp = 100),
                    message(5, Category.PROMOTIONAL, timestamp = 100),
                ),
            )

            assertThat(repository.countOtpOlderThan(cutoffMs = 300)).isEqualTo(2)
            assertThat(repository.countOtpOlderThan(cutoffMs = Long.MAX_VALUE)).isEqualTo(3)
            // Strict `<`: a message stamped exactly at the cutoff is kept.
            assertThat(repository.countOtpOlderThan(cutoffMs = 100)).isEqualTo(0)
        }

    @Test
    fun `delete removes only otp messages and syncs the system provider`() =
        runBlocking<Unit> {
            db.messageDao().insertAll(
                listOf(
                    message(1, Category.OTP, timestamp = 100, systemSmsId = 11),
                    message(2, Category.OTP, timestamp = 900, systemSmsId = 12),
                    message(3, Category.PERSONAL, timestamp = 100, systemSmsId = 13),
                    message(4, Category.IMPORTANT, timestamp = 100, systemSmsId = 14),
                ),
            )

            val deleted = repository.deleteOtpOlderThan(cutoffMs = 500)

            assertThat(deleted).isEqualTo(1)
            assertThat(db.messageDao().getAll().map { it.id }).containsExactly(2L, 3L, 4L)
            assertThat(deletedFromProvider.flatten()).containsExactly(11L)
        }

    @Test
    fun `a message carrying an otp code but categorized elsewhere survives`() =
        runBlocking<Unit> {
            // Eligibility rule: category == OTP, nothing else. An IMPORTANT
            // bank alert that happens to contain a code is not an OTP message.
            db.messageDao().insertAll(
                listOf(
                    message(1, Category.OTP, timestamp = 100, extractedOtp = "123456"),
                    message(2, Category.IMPORTANT, timestamp = 100, extractedOtp = "654321"),
                ),
            )

            val deleted = repository.deleteOtpOlderThan(cutoffMs = Long.MAX_VALUE)

            assertThat(deleted).isEqualTo(1)
            assertThat(db.messageDao().getAll().map { it.id }).containsExactly(2L)
        }

    @Test
    fun `deletion beyond the sqlite variable limit runs in bounded chunks`() =
        runBlocking<Unit> {
            val count = 1_200
            db.messageDao().insertAll(
                (1L..count).map { message(it, Category.OTP, timestamp = it, systemSmsId = 10_000 + it) },
            )

            val deleted = repository.deleteOtpOlderThan(cutoffMs = Long.MAX_VALUE)

            assertThat(deleted).isEqualTo(count)
            assertThat(db.messageDao().getAll()).isEmpty()
            assertThat(deletedFromProvider.flatten()).hasSize(count)
            deletedFromProvider.forEach { chunk ->
                assertThat(chunk.size).isAtMost(SqliteChunker.MAX_VARIABLES)
            }
            assertThat(deletedFromProvider.size).isAtLeast(2)
        }

    @Test
    fun `empty selection deletes nothing and never touches the provider`() =
        runBlocking<Unit> {
            db.messageDao().insertAll(
                listOf(message(1, Category.PERSONAL, timestamp = 100, systemSmsId = 11)),
            )

            val deleted = repository.deleteOtpOlderThan(cutoffMs = Long.MAX_VALUE)

            assertThat(deleted).isEqualTo(0)
            assertThat(db.messageDao().getAll()).hasSize(1)
            assertThat(deletedFromProvider).isEmpty()
        }

    /** The cleanup never touches the rule loader; a no-op store keeps setup light. */
    private object NoopDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }
}
