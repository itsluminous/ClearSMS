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

/**
 * The highlight-jump contract on the REAL query pair: for every message,
 * `newerCountInThread` (the position the pager's initialKey is built from)
 * must equal that message's actual index in `pagingThread`'s
 * `ORDER BY timestamp DESC, id DESC` output. The trap is tied timestamps:
 * counting `timestamp >` alone under-counts same-timestamp rows with a
 * higher id, the initial page falls short of a very old target, and the
 * highlight never attaches (or the wrong message flashes).
 */
@RunWith(RobolectricTestRunner::class)
class MessagePositionDaoTest {
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
        timestamp: Long,
        threadId: Long = 1L,
        deletedAt: Long? = null,
    ) = MessageEntity(
        id = id,
        threadId = threadId,
        sender = "sender-$threadId",
        normalizedSender = "sender-$threadId",
        body = "body $id",
        timestamp = timestamp,
        category = Category.PERSONAL,
        deletedAt = deletedAt,
    )

    private fun pagedIds(threadId: Long = 1L): List<Long> =
        runBlocking {
            val result =
                dao.pagingThread(threadId).load(
                    PagingSource.LoadParams.Refresh(key = null, loadSize = 500, placeholdersEnabled = false),
                )
            (result as PagingSource.LoadResult.Page).data.map { it.id }
        }

    @Test
    fun `counted position equals the pager's actual index for every message`() =
        runBlocking<Unit> {
            dao.insertAll((1L..40L).map { message(id = it, timestamp = 1_000L + it) })

            val order = pagedIds()
            order.forEachIndexed { index, id ->
                assertThat(dao.newerCountInThread(threadId = 1L, messageId = id)).isEqualTo(index)
            }
        }

    @Test
    fun `tied timestamps - position still matches the pager, which breaks ties on id DESC`() =
        runBlocking<Unit> {
            // A bulk import stamps a whole run with one timestamp. Pager
            // order: 9,8,7,6,5 (ts=2000) then 2,1 (ts=1000).
            dao.insertAll(
                listOf(
                    message(id = 1, timestamp = 1_000),
                    message(id = 2, timestamp = 1_000),
                    message(id = 5, timestamp = 2_000),
                    message(id = 6, timestamp = 2_000),
                    message(id = 7, timestamp = 2_000),
                    message(id = 8, timestamp = 2_000),
                    message(id = 9, timestamp = 2_000),
                ),
            )

            val order = pagedIds()
            assertThat(order).isEqualTo(listOf(9L, 8L, 7L, 6L, 5L, 2L, 1L))
            order.forEachIndexed { index, id ->
                assertThat(dao.newerCountInThread(threadId = 1L, messageId = id)).isEqualTo(index)
            }
        }

    @Test
    fun `binned and other-thread rows never shift the position`() =
        runBlocking<Unit> {
            dao.insertAll(
                listOf(
                    message(id = 1, timestamp = 100),
                    message(id = 2, timestamp = 200, deletedAt = 999L),
                    message(id = 3, timestamp = 300),
                    message(id = 4, timestamp = 400, threadId = 2L),
                ),
            )

            // Thread 1 pager order: 3, 1 (the binned 2 and thread-2's 4 excluded).
            assertThat(pagedIds()).isEqualTo(listOf(3L, 1L))
            assertThat(dao.newerCountInThread(threadId = 1L, messageId = 3)).isEqualTo(0)
            assertThat(dao.newerCountInThread(threadId = 1L, messageId = 1)).isEqualTo(1)
        }

    @Test
    fun `a target no longer present resolves to position zero - open at newest`() =
        runBlocking<Unit> {
            dao.insertAll(listOf(message(id = 1, timestamp = 100)))
            assertThat(dao.newerCountInThread(threadId = 1L, messageId = 99)).isEqualTo(0)
        }
}
