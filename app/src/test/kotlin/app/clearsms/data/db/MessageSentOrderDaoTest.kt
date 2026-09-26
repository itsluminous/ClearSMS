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
 * The sent-time ordering (GitHub #45) on the REAL queries. The reporter's
 * case: with no signal, two messages from one person arrive together and
 * in the WRONG order relative to when they were sent. Under the default
 * received sort nothing changes (today's behaviour); under the sent sort
 * they land in send order, a message with no network timestamp falls back
 * to its received time (never the epoch), and - the regression that would
 * hurt most - `newerCountInThreadBySent` equals the pager's actual index
 * for every message, ties included, so the search jump still lands.
 */
@RunWith(RobolectricTestRunner::class)
class MessageSentOrderDaoTest {
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
        dateSent: Long? = null,
        threadId: Long = 1L,
        outgoing: Boolean = false,
    ) = MessageEntity(
        id = id,
        threadId = threadId,
        sender = "sender-$threadId",
        normalizedSender = "sender-$threadId",
        body = "body $id",
        timestamp = timestamp,
        category = Category.PERSONAL,
        dateSent = dateSent,
        isOutgoing = outgoing,
    )

    private fun pagedIds(source: PagingSource<Int, MessageEntity>): List<Long> =
        runBlocking {
            val result =
                source.load(
                    PagingSource.LoadParams.Refresh(key = null, loadSize = 500, placeholdersEnabled = false),
                )
            (result as PagingSource.LoadResult.Page).data.map { it.id }
        }

    /** The reporter's case: "Are you coming?" sent at 10:00, "Yes, at 6" sent at 10:05, both received 10:30, in the wrong order. */
    private fun insertReportersCase() =
        runBlocking {
            dao.insertAll(
                listOf(
                    // Arrived (and was inserted) FIRST, but was sent SECOND.
                    message(id = 1, timestamp = 10_30_00L, dateSent = 10_05_00L),
                    // Arrived a moment later, but was sent FIRST.
                    message(id = 2, timestamp = 10_30_01L, dateSent = 10_00_00L),
                    // An older message with no network timestamp at all.
                    message(id = 3, timestamp = 9_00_00L, dateSent = null),
                ),
            )
        }

    @Test
    fun `received sort is unchanged - newest arrival first, whatever the sent times`() {
        insertReportersCase()

        assertThat(pagedIds(dao.pagingThread(1L))).isEqualTo(listOf(2L, 1L, 3L))
    }

    @Test
    fun `sent sort puts the reporter's out-of-order arrivals back in send order`() {
        insertReportersCase()

        // Newest-first by sent time: id 1 (sent 10:05) above id 2 (sent 10:00);
        // id 3 (no sent time) sorts by its received 09:00, not at the epoch.
        assertThat(pagedIds(dao.pagingThreadBySent(1L))).isEqualTo(listOf(1L, 2L, 3L))
    }

    @Test
    fun `an unknown sent time falls back to the received time - never the epoch`() =
        runBlocking<Unit> {
            dao.insertAll(
                listOf(
                    message(id = 1, timestamp = 1_000L, dateSent = 900L),
                    // Received last, sent time unknown: stays newest under
                    // both sorts. If 0/null were sorted literally it would sink.
                    message(id = 2, timestamp = 5_000L, dateSent = null),
                    message(id = 3, timestamp = 3_000L, dateSent = 2_900L),
                ),
            )

            assertThat(pagedIds(dao.pagingThread(1L))).isEqualTo(listOf(2L, 3L, 1L))
            assertThat(pagedIds(dao.pagingThreadBySent(1L))).isEqualTo(listOf(2L, 3L, 1L))
        }

    @Test
    fun `sent-order position equals the sent pager's index for every message, ties included`() =
        runBlocking<Unit> {
            // Bulk-import style ties on both keys plus the reporter's swap.
            dao.insertAll(
                listOf(
                    message(id = 1, timestamp = 1_000L, dateSent = 950L),
                    message(id = 2, timestamp = 1_000L, dateSent = 950L),
                    message(id = 3, timestamp = 1_000L, dateSent = null),
                    message(id = 4, timestamp = 2_000L, dateSent = 1_500L),
                    message(id = 5, timestamp = 2_000L, dateSent = 1_500L),
                    message(id = 6, timestamp = 2_001L, dateSent = 1_200L),
                    message(id = 7, timestamp = 3_000L, dateSent = null),
                    message(id = 8, timestamp = 2_500L, dateSent = 2_600L),
                ),
            )

            val sentOrder = pagedIds(dao.pagingThreadBySent(1L))
            assertThat(sentOrder).isEqualTo(listOf(7L, 8L, 5L, 4L, 6L, 3L, 2L, 1L))
            sentOrder.forEachIndexed { index, id ->
                assertThat(dao.newerCountInThreadBySent(threadId = 1L, messageId = id)).isEqualTo(index)
            }
            // And the received pair still agrees with itself on the same data.
            val receivedOrder = pagedIds(dao.pagingThread(1L))
            receivedOrder.forEachIndexed { index, id ->
                assertThat(dao.newerCountInThread(threadId = 1L, messageId = id)).isEqualTo(index)
            }
        }

    @Test
    fun `inbox under sent sort ranks threads by their latest message's sent time`() =
        runBlocking<Unit> {
            dao.insertAll(
                listOf(
                    // Thread 1's latest arrived last but was sent earliest.
                    message(id = 1, timestamp = 3_000L, dateSent = 1_000L, threadId = 1L),
                    // Thread 2's latest has no sent time: received time counts.
                    message(id = 2, timestamp = 2_000L, dateSent = null, threadId = 2L),
                    // Thread 3's latest was sent most recently.
                    message(id = 3, timestamp = 2_500L, dateSent = 2_400L, threadId = 3L),
                ),
            )

            fun inboxThreads(source: PagingSource<Int, InboxThreadRow>) =
                runBlocking {
                    val result =
                        source.load(
                            PagingSource.LoadParams.Refresh(key = null, loadSize = 50, placeholdersEnabled = false),
                        )
                    (result as PagingSource.LoadResult.Page).data.map { it.message.threadId }
                }

            assertThat(inboxThreads(dao.pagingInbox(null, false, false))).isEqualTo(listOf(1L, 3L, 2L))
            assertThat(inboxThreads(dao.pagingInboxBySent(null, false, false))).isEqualTo(listOf(3L, 2L, 1L))
        }
}
