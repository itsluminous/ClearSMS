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

@RunWith(RobolectricTestRunner::class)
class BulkMessageOpsTest {
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
        threadId: Long = 1L,
        systemSmsId: Long? = null,
        isRead: Boolean = false,
        timestamp: Long = id,
    ) = MessageEntity(
        id = id,
        threadId = threadId,
        sender = "sender-$threadId",
        normalizedSender = "sender-$threadId",
        body = "body $id",
        timestamp = timestamp,
        isRead = isRead,
        category = Category.PERSONAL,
        systemSmsId = systemSmsId,
    )

    @Test
    fun `bulk delete removes rows and syncs the system provider`() =
        runBlocking<Unit> {
            db.messageDao().insertAll(
                listOf(
                    message(1, systemSmsId = 101),
                    message(2, systemSmsId = 102),
                    message(3, systemSmsId = null),
                    message(4, systemSmsId = 104),
                ),
            )

            repository.deleteMessages(listOf(1L, 2L, 3L))

            assertThat(db.messageDao().getAll().map { it.id }).containsExactly(4L)
            // Only rows that exist in the provider are forwarded for deletion.
            assertThat(deletedFromProvider.flatten()).containsExactly(101L, 102L)
        }

    @Test
    fun `thread delete removes every message of the selected threads`() =
        runBlocking<Unit> {
            db.messageDao().insertAll(
                listOf(
                    message(1, threadId = 1, systemSmsId = 11),
                    message(2, threadId = 1, systemSmsId = 12),
                    message(3, threadId = 2, systemSmsId = 21),
                    message(4, threadId = 3),
                ),
            )

            repository.deleteThreads(listOf(1L, 2L))

            assertThat(db.messageDao().getAll().map { it.id }).containsExactly(4L)
            assertThat(deletedFromProvider.flatten()).containsExactly(11L, 12L, 21L)
        }

    @Test
    fun `bulk delete chunks id lists beyond the sqlite variable limit`() =
        runBlocking<Unit> {
            val count = 1_200
            db.messageDao().insertAll(
                (1L..count).map { message(it, systemSmsId = 10_000 + it) },
            )

            repository.deleteMessages((1L..count).toList())

            assertThat(db.messageDao().getAll()).isEmpty()
            assertThat(deletedFromProvider.flatten()).hasSize(count.toInt())
            deletedFromProvider.forEach { chunk ->
                assertThat(chunk.size).isAtMost(SqliteChunker.MAX_VARIABLES)
            }
            assertThat(deletedFromProvider.size).isAtLeast(2)
        }

    @Test
    fun `setReadForThreads marks every message in the threads`() =
        runBlocking<Unit> {
            db.messageDao().insertAll(
                listOf(
                    message(1, threadId = 1),
                    message(2, threadId = 1),
                    message(3, threadId = 2),
                ),
            )

            repository.setReadForThreads(listOf(1L), read = true)

            val all = db.messageDao().getAll()
            assertThat(all.filter { it.threadId == 1L }.all { it.isRead }).isTrue()
            assertThat(all.single { it.threadId == 2L }.isRead).isFalse()
        }

    @Test
    fun `archiveThreads hides threads from the inbox view`() =
        runBlocking<Unit> {
            db.messageDao().insertAll(
                listOf(
                    message(1, threadId = 1),
                    message(2, threadId = 2),
                ),
            )

            repository.archiveThreads(listOf(1L))

            assertThat(repository.inboxThreadIds(category = null, unreadOnly = false))
                .containsExactly(2L)
        }

    @Test
    fun `unreadCountInThreads counts only unread messages of the selection`() =
        runBlocking<Unit> {
            db.messageDao().insertAll(
                listOf(
                    message(1, threadId = 1, isRead = false),
                    message(2, threadId = 1, isRead = true),
                    message(3, threadId = 2, isRead = false),
                ),
            )

            assertThat(repository.unreadCountInThreads(listOf(1L))).isEqualTo(1)
            assertThat(repository.unreadCountInThreads(listOf(1L, 2L))).isEqualTo(2)
        }

    @Test
    fun `bodiesInOrder returns chronological bodies for copy`() =
        runBlocking<Unit> {
            db.messageDao().insertAll(
                listOf(
                    message(1, timestamp = 300),
                    message(2, timestamp = 100),
                    message(3, timestamp = 200),
                ),
            )

            assertThat(repository.bodiesInOrder(listOf(1L, 2L, 3L)))
                .containsExactly("body 2", "body 3", "body 1")
                .inOrder()
        }

    /** Bulk ops never touch the rule loader; a no-op store keeps setup light. */
    private object NoopDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }
}
