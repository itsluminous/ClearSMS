package app.clearsms.data.db

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.domain.model.Category
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MessagePagingSourceTest {
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
        threadId: Long = 1L,
        timestamp: Long = id,
        category: Category = Category.PERSONAL,
    ) = MessageEntity(
        id = id,
        threadId = threadId,
        sender = "sender-$threadId",
        normalizedSender = "sender-$threadId",
        body = "body $id",
        timestamp = timestamp,
        category = category,
    )

    private suspend fun <T : Any> load(
        source: PagingSource<Int, T>,
        loadSize: Int,
    ): List<T> {
        val result =
            source.load(
                PagingSource.LoadParams.Refresh(key = null, loadSize = loadSize, placeholdersEnabled = false),
            )
        return (result as PagingSource.LoadResult.Page).data
    }

    @Test
    fun `thread paging loads only the requested window, newest first`() =
        runBlocking<Unit> {
            dao.insertAll((1L..30L).map { message(it) })

            val page = load(dao.pagingThread(threadId = 1L), loadSize = 10)

            assertThat(page).hasSize(10)
            assertThat(page.map { it.id }).isEqualTo((30L downTo 21L).toList())
        }

    @Test
    fun `inbox paging returns the latest message per thread, newest thread first`() =
        runBlocking<Unit> {
            dao.insertAll(
                listOf(
                    message(1, threadId = 1, timestamp = 100),
                    message(2, threadId = 1, timestamp = 200),
                    message(3, threadId = 2, timestamp = 300),
                    message(4, threadId = 3, timestamp = 50),
                ),
            )

            val page = load(dao.pagingInbox(category = null, unreadOnly = false), loadSize = 10)

            assertThat(page.map { it.message.id }).isEqualTo(listOf(3L, 2L, 4L))
        }

    @Test
    fun `inbox paging honors the category filter`() =
        runBlocking<Unit> {
            dao.insertAll(
                listOf(
                    message(1, threadId = 1, category = Category.PROMOTIONAL),
                    message(2, threadId = 2, category = Category.IMPORTANT),
                ),
            )

            val page = load(dao.pagingInbox(category = Category.IMPORTANT, unreadOnly = false), loadSize = 10)

            assertThat(page.map { it.message.id }).containsExactly(2L)
        }

    @Test
    fun `inbox rows carry their thread's draft text`() =
        runBlocking<Unit> {
            dao.insertAll(
                listOf(
                    message(1, threadId = 1, timestamp = 100),
                    message(2, threadId = 2, timestamp = 200),
                ),
            )
            db.draftDao().upsert(DraftEntity(threadId = 1, text = "half-typed reply", updatedAt = 1L))

            val page = load(dao.pagingInbox(category = null, unreadOnly = false), loadSize = 10)

            assertThat(page.first { it.message.threadId == 1L }.draftText).isEqualTo("half-typed reply")
            assertThat(page.first { it.message.threadId == 2L }.draftText).isNull()
        }

    @Test
    fun `a draft never changes inbox order or the unread filter`() =
        runBlocking<Unit> {
            // Thread 1 is older and read; giving it a draft must not float it
            // above thread 2 or surface it under the unread-only filter.
            dao.insertAll(
                listOf(
                    message(1, threadId = 1, timestamp = 100).copy(isRead = true),
                    message(2, threadId = 2, timestamp = 200),
                ),
            )
            db.draftDao().upsert(DraftEntity(threadId = 1, text = "draft", updatedAt = 1L))

            val all = load(dao.pagingInbox(category = null, unreadOnly = false), loadSize = 10)
            assertThat(all.map { it.message.threadId }).isEqualTo(listOf(2L, 1L))

            val unread = load(dao.pagingInbox(category = null, unreadOnly = true), loadSize = 10)
            assertThat(unread.map { it.message.threadId }).containsExactly(2L)
        }
}
