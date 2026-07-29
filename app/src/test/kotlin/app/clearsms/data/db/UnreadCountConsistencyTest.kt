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
 * The unread badge must always equal the number of rows the inbox unread
 * filter shows: a conversation counts as unread only when its representative
 * (latest) message is unread. Previously the badge counted every unread
 * message, so a thread whose newest message was read but which still held an
 * older unread message inflated the badge while the filter showed nothing.
 */
@RunWith(RobolectricTestRunner::class)
class UnreadCountConsistencyTest {
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
    fun tearDown() = db.close()

    private fun message(
        id: Long,
        threadId: Long,
        read: Boolean,
        category: Category = Category.IMPORTANT,
    ) = MessageEntity(
        id = id,
        threadId = threadId,
        sender = "s$threadId",
        normalizedSender = "s$threadId",
        body = "body $id",
        timestamp = id,
        isRead = read,
        isArchived = false,
        category = category,
    )

    @Test
    fun `a thread whose latest message is read is not counted as unread`() =
        runBlocking<Unit> {
            // Thread 1: older unread (id 1), newest read (id 2) -> conversation is read.
            dao.insert(message(id = 1, threadId = 1, read = false))
            dao.insert(message(id = 2, threadId = 1, read = true))

            val badge = dao.observeUnreadCounts().first().sumOf { it.count }
            val filterRows = dao.observeInbox(category = null, unreadOnly = true).first().size

            assertThat(badge).isEqualTo(0)
            assertThat(filterRows).isEqualTo(0)
        }

    @Test
    fun `a thread whose latest message is unread is counted once and shown`() =
        runBlocking<Unit> {
            // Thread 1: newest is unread. Thread 2: fully read.
            dao.insert(message(id = 1, threadId = 1, read = true))
            dao.insert(message(id = 2, threadId = 1, read = false))
            dao.insert(message(id = 3, threadId = 2, read = true))

            val counts = dao.observeUnreadCounts().first()
            val badge = counts.sumOf { it.count }
            val filterRows = dao.observeInbox(category = null, unreadOnly = true).first()

            assertThat(badge).isEqualTo(1)
            assertThat(filterRows.map { it.threadId }).containsExactly(1L)
        }

    @Test
    fun `badge equals filter row count across a mixed inbox`() =
        runBlocking<Unit> {
            // t1 latest unread (counts), t2 latest read but older unread (does NOT),
            // t3 latest unread promotional (counts), t4 fully read (does NOT).
            dao.insert(message(id = 1, threadId = 1, read = false))
            dao.insert(message(id = 2, threadId = 2, read = false))
            dao.insert(message(id = 3, threadId = 2, read = true))
            dao.insert(message(id = 4, threadId = 3, read = false, category = Category.PROMOTIONAL))
            dao.insert(message(id = 5, threadId = 4, read = true))

            val badge = dao.observeUnreadCounts().first().sumOf { it.count }
            val filterRows = dao.observeInbox(category = null, unreadOnly = true).first().size

            assertThat(badge).isEqualTo(2)
            assertThat(filterRows).isEqualTo(2)
        }
}
