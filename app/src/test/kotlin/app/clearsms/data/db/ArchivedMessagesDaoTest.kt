package app.clearsms.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.domain.model.Category
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Archived threads live only in the archived view: never in the inbox,
 * never in the unread counts, and unarchiving restores them.
 */
@RunWith(RobolectricTestRunner::class)
class ArchivedMessagesDaoTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var dao: MessageDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.messageDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun message(
        id: Long,
        threadId: Long,
        archived: Boolean = false,
        read: Boolean = false,
    ) = MessageEntity(
        id = id,
        threadId = threadId,
        sender = "sender-$threadId",
        normalizedSender = "sender-$threadId",
        body = "body $id",
        timestamp = id,
        isRead = read,
        isArchived = archived,
        category = Category.PERSONAL,
    )

    @Test
    fun `archived threads are excluded from the inbox`() =
        runBlocking<Unit> {
            dao.insert(message(1, threadId = 1, archived = true))
            dao.insert(message(2, threadId = 2))

            val inbox = dao.observeInbox(category = null, unreadOnly = false).first()

            assertThat(inbox.map { it.threadId }).containsExactly(2L)
        }

    @Test
    fun `archived messages are excluded from unread counts`() =
        runBlocking<Unit> {
            dao.insert(message(1, threadId = 1, archived = true, read = false))
            dao.insert(message(2, threadId = 2, read = false))

            val counts = dao.observeUnreadCounts().first()

            assertThat(counts.single { it.category == Category.PERSONAL }.count).isEqualTo(1)
        }

    @Test
    fun `archived view lists the latest message per archived thread`() =
        runBlocking<Unit> {
            dao.insert(message(1, threadId = 1, archived = true))
            dao.insert(message(2, threadId = 1, archived = true))
            dao.insert(message(3, threadId = 2))

            val archived = dao.observeArchived().first()

            assertThat(archived.map { it.id }).containsExactly(2L)
        }

    @Test
    fun `unarchive restores the thread to the inbox`() =
        runBlocking<Unit> {
            dao.insert(message(1, threadId = 1, archived = true))

            dao.setArchivedForThreads(listOf(1L), archived = false)

            assertThat(dao.observeArchived().first()).isEmpty()
            assertThat(dao.observeInbox(null, false).first().map { it.threadId }).containsExactly(1L)
        }

    @Test
    fun `archived view is empty when nothing is archived`() =
        runBlocking<Unit> {
            dao.insert(message(1, threadId = 1))

            assertThat(dao.observeArchived().first()).isEmpty()
            assertThat(dao.archivedThreadIds()).isEmpty()
        }

    @Test
    fun `archived thread ids back select-all`() =
        runBlocking<Unit> {
            dao.insert(message(1, threadId = 1, archived = true))
            dao.insert(message(2, threadId = 2, archived = true))
            dao.insert(message(3, threadId = 3))

            assertThat(dao.archivedThreadIds()).containsExactly(1L, 2L)
        }
}
