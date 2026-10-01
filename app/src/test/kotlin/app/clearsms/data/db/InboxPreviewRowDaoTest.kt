package app.clearsms.data.db

import android.content.Context
import androidx.paging.PagingSource
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
 * The inbox preview row is the thread's NEWEST MESSAGE, never its
 * newest ROW. The operator's report: recent threads previewed correctly
 * but a two-year-old thread previewed its FIRST message, because the
 * per-thread representative used to be picked by `MAX(id)` (Room insert
 * order). Insert order and message order only diverge in old threads -
 * sent-time backfills, catch-up imports, provider-id reuse - which is why
 * only those showed the wrong preview.
 *
 * Every per-thread list (inbox, paged inbox under both sort settings,
 * select-all ids, badge counts, archived list and ids) now goes through
 * [LatestPerThreadSql], ordered by the same key as the conversation pager
 * (`timestamp DESC, id DESC`, or the sent-time key under that setting), so
 * the preview can never disagree with the bubble at the bottom of the
 * thread. All fixtures are synthetic.
 */
@RunWith(RobolectricTestRunner::class)
class InboxPreviewRowDaoTest {
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
        timestamp: Long,
        threadId: Long = 1L,
        dateSent: Long? = null,
        read: Boolean = true,
        archived: Boolean = false,
        deletedAt: Long? = null,
        category: Category = Category.PERSONAL,
    ) = MessageEntity(
        id = id,
        threadId = threadId,
        sender = "sender-$threadId",
        normalizedSender = "sender-$threadId",
        body = "body $id",
        timestamp = timestamp,
        dateSent = dateSent,
        isRead = read,
        isArchived = archived,
        deletedAt = deletedAt,
        category = category,
    )

    private fun <T : Any> firstPage(source: PagingSource<Int, T>): List<T> =
        runBlocking {
            val result =
                source.load(
                    PagingSource.LoadParams.Refresh(key = null, loadSize = 500, placeholdersEnabled = false),
                )
            (result as PagingSource.LoadResult.Page).data
        }

    /**
     * The operator's exact state: the thread's newest message (by time) has
     * a LOWER row id than an older message in the same thread - the way a
     * backfilled or re-imported old conversation ends up on disk.
     */
    private fun insertOldThreadWithCrossedIds() =
        runBlocking {
            dao.insertAll(
                listOf(
                    // Inserted first, but the NEWEST message of the thread.
                    message(id = 1, timestamp = 2_000L),
                    // Inserted second (highest row id), but the OLDEST message.
                    message(id = 2, timestamp = 1_000L),
                ),
            )
        }

    @Test
    fun `regression - an old thread previews its newest message, not its highest row id`() =
        runBlocking<Unit> {
            insertOldThreadWithCrossedIds()

            val inbox = dao.observeInbox(category = null, unreadOnly = false).first()
            assertThat(inbox.map { it.id }).containsExactly(1L)

            val paged = firstPage(dao.pagingInbox(category = null, unreadOnly = false))
            assertThat(paged.map { it.message.id }).containsExactly(1L)
        }

    @Test
    fun `equal timestamps tie-break on the higher id, like the conversation pager`() =
        runBlocking<Unit> {
            dao.insertAll(
                listOf(
                    message(id = 10, timestamp = 5_000L),
                    message(id = 11, timestamp = 5_000L),
                    message(id = 12, timestamp = 5_000L),
                ),
            )

            val preview = dao.observeInbox(category = null, unreadOnly = false).first().single()
            val bottomOfThread = firstPage(dao.pagingThread(1L)).first()
            assertThat(preview.id).isEqualTo(12L)
            assertThat(preview.id).isEqualTo(bottomOfThread.id)
        }

    @Test
    fun `a binned newest message never becomes the preview, and the bin still lists it`() =
        runBlocking<Unit> {
            insertOldThreadWithCrossedIds()
            dao.insertAll(listOf(message(id = 3, timestamp = 3_000L, deletedAt = 9_999L)))

            val preview = dao.observeInbox(category = null, unreadOnly = false).first().single()
            assertThat(preview.id).isEqualTo(1L)
            assertThat(firstPage(dao.pagingInbox(null, false)).single().message.id).isEqualTo(1L)
            assertThat(dao.observeBin().first().map { it.id }).containsExactly(3L)
        }

    @Test
    fun `an archived thread previews its newest message under the crossed-id layout`() =
        runBlocking<Unit> {
            dao.insertAll(
                listOf(
                    message(id = 1, timestamp = 2_000L, archived = true),
                    message(id = 2, timestamp = 1_000L, archived = true),
                    // A live thread that must not leak into the archived view.
                    message(id = 3, timestamp = 500L, threadId = 2L),
                ),
            )

            assertThat(dao.observeArchived().first().map { it.id }).containsExactly(1L)
            assertThat(dao.archivedThreadIds()).containsExactly(1L)
            assertThat(dao.observeInbox(null, false).first().map { it.id }).containsExactly(3L)
        }

    @Test
    fun `unread and category filters judge the newest message, not the highest row id`() =
        runBlocking<Unit> {
            dao.insertAll(
                listOf(
                    // Newest message: read, IMPORTANT, lowest row id.
                    message(id = 1, timestamp = 2_000L, read = true, category = Category.IMPORTANT),
                    // Oldest message: unread, PROMOTIONAL, highest row id.
                    message(id = 2, timestamp = 1_000L, read = false, category = Category.PROMOTIONAL),
                ),
            )

            // Unread filter: the thread's representative is read, so nothing shows ...
            assertThat(dao.observeInbox(category = null, unreadOnly = true).first()).isEmpty()
            assertThat(firstPage(dao.pagingInbox(null, true))).isEmpty()
            assertThat(dao.inboxThreadIds(category = null, unreadOnly = true)).isEmpty()
            // ... and the badge agrees with the filter.
            assertThat(dao.observeUnreadCounts().first()).isEmpty()

            // Category filter: the thread lives under its newest message's pill.
            assertThat(dao.observeInbox(Category.IMPORTANT, false).first().map { it.id }).containsExactly(1L)
            assertThat(dao.observeInbox(Category.PROMOTIONAL, false).first()).isEmpty()
            assertThat(dao.inboxThreadIds(Category.IMPORTANT, false)).containsExactly(1L)
        }

    /**
     * The #45 reporter's thread: two messages arrive together (same received
     * time) in the WRONG order relative to when they were sent. Under each
     * sort setting the inbox preview must be the message the conversation
     * shows at the bottom under THAT setting - and the two settings pick
     * different messages here, which is exactly why the preview has to
     * follow the setting rather than always use the received time.
     */
    @Test
    fun `the preview agrees with the bottom of the thread under both sort settings`() =
        runBlocking<Unit> {
            dao.insertAll(
                listOf(
                    // Arrived first (lower id), but SENT later.
                    message(id = 1, timestamp = 10_30_00L, dateSent = 10_05_00L),
                    // Arrived second, but SENT earlier.
                    message(id = 2, timestamp = 10_30_00L, dateSent = 10_00_00L),
                ),
            )

            val receivedPreview = firstPage(dao.pagingInbox(null, false)).single().message.id
            val receivedBottom = firstPage(dao.pagingThread(1L)).first().id
            assertThat(receivedPreview).isEqualTo(receivedBottom)
            assertThat(receivedPreview).isEqualTo(2L)

            val sentPreview = firstPage(dao.pagingInboxBySent(null, false)).single().message.id
            val sentBottom = firstPage(dao.pagingThreadBySent(1L)).first().id
            assertThat(sentPreview).isEqualTo(sentBottom)
            assertThat(sentPreview).isEqualTo(1L)
        }

    /**
     * This join runs on every inbox page, so it must stay on the
     * `(threadId, timestamp)` index: the inner grouped pass is the same
     * scan the old `MAX(id)` query paid for, the new second pass must be
     * an EQUALITY PROBE of that index (`threadId=? AND timestamp=?`), and
     * the outer table must be reached by primary key, never scanned.
     */
    @Test
    fun `the inbox query probes the (threadId, timestamp) index and never scans the outer table`() {
        val sql =
            """
            SELECT m.* FROM messages m
            ${LatestPerThreadSql.JOIN_BY_RECEIVED}
            WHERE m.isArchived = 0
            ORDER BY m.timestamp DESC
            """.trimIndent()
        val plan = explain(sql)

        assertThat(plan.any { it.contains("USING INDEX index_messages_threadId_timestamp (threadId=? AND timestamp=?)") })
            .isTrue()
        assertThat(plan.any { it.contains("SEARCH m USING INTEGER PRIMARY KEY") || it.contains("SEARCH m USING INDEX") })
            .isTrue()
        assertThat(plan.none { it.startsWith("SCAN m") || it.contains("SCAN m ") }).isTrue()
    }

    /**
     * The sent key has no index (nor does the sent-ordered pager's ORDER
     * BY - that is #45's accepted cost), but pass 2 must still be one
     * thread-scoped probe per thread rather than a second pass over the
     * whole table.
     */
    @Test
    fun `the sent-ordered inbox query still probes per thread rather than rescanning messages`() {
        val sql =
            """
            SELECT m.* FROM messages m
            ${LatestPerThreadSql.JOIN_BY_SENT}
            WHERE m.isArchived = 0
            ORDER BY COALESCE(m.dateSent, m.timestamp) DESC, m.id DESC
            """.trimIndent()
        val plan = explain(sql)

        assertThat(plan.any { it.startsWith("SCAN newest") }).isTrue()
        assertThat(plan.any { it.startsWith("SEARCH messages USING INDEX index_messages_threadId") && it.contains("(threadId=?") })
            .isTrue()
        assertThat(plan.none { it.startsWith("SCAN m") || it.contains("SCAN m ") }).isTrue()
    }

    private fun explain(sql: String): List<String> {
        val rows = mutableListOf<String>()
        db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
            val detail = cursor.getColumnIndex("detail")
            while (cursor.moveToNext()) rows += cursor.getString(detail)
        }
        return rows
    }

    @Test
    fun `recent threads keep previewing their newest message`() =
        runBlocking<Unit> {
            dao.insertAll(
                listOf(
                    message(id = 1, timestamp = 1_000L, threadId = 7L),
                    message(id = 2, timestamp = 2_000L, threadId = 7L),
                    message(id = 3, timestamp = 3_000L, threadId = 7L),
                ),
            )
            assertThat(
                dao
                    .observeInbox(null, false)
                    .first()
                    .single()
                    .id,
            ).isEqualTo(3L)
        }
}
