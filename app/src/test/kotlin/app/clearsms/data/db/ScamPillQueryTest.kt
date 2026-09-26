package app.clearsms.data.db

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SubCategory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The "Spam" pill is a scam-FLAG filter, not a category: `scamOnly` keeps
 * exactly the threads whose latest message carries [SubCategory.SCAM],
 * across primary categories, and composes with the unread flag. Fixtures are
 * synthetic.
 */
@RunWith(RobolectricTestRunner::class)
class ScamPillQueryTest {
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
        category: Category,
        subCategory: SubCategory? = null,
        isRead: Boolean = true,
    ) = MessageEntity(
        id = id,
        threadId = threadId,
        sender = "sender-$threadId",
        normalizedSender = "sender-$threadId",
        body = "body $id",
        timestamp = id,
        category = category,
        subCategory = subCategory,
        isRead = isRead,
    )

    private suspend fun load(source: PagingSource<Int, InboxThreadRow>): List<Long> {
        val result =
            source.load(
                PagingSource.LoadParams.Refresh(key = null, loadSize = 50, placeholdersEnabled = false),
            )
        return (result as PagingSource.LoadResult.Page).data.map { it.message.threadId }
    }

    private suspend fun seed() {
        dao.insertAll(
            listOf(
                // Heuristic scam hit: PROMOTIONAL + SCAM flag.
                message(1, threadId = 1, category = Category.PROMOTIONAL, subCategory = SubCategory.SCAM),
                // Plain promotion - not flagged.
                message(2, threadId = 2, category = Category.PROMOTIONAL, subCategory = SubCategory.OFFER),
                // A `scam` rule can flag any category: UNKNOWN + SCAM, unread.
                message(3, threadId = 3, category = Category.UNKNOWN, subCategory = SubCategory.SCAM, isRead = false),
                // Important, unflagged.
                message(4, threadId = 4, category = Category.IMPORTANT, subCategory = SubCategory.TRANSACTION),
                // A thread whose OLDER message was flagged but whose latest is not:
                // the inbox row is the latest message, so it is not in the set.
                message(5, threadId = 5, category = Category.PERSONAL, subCategory = SubCategory.SCAM),
                message(6, threadId = 5, category = Category.PERSONAL),
            ),
        )
    }

    @Test
    fun `scam pill returns exactly the threads whose latest message is scam-flagged`() =
        runBlocking<Unit> {
            seed()
            val flagged = load(dao.pagingInbox(category = null, unreadOnly = false, scamOnly = true))
            assertThat(flagged).containsExactly(3L, 1L)
        }

    @Test
    fun `scam pill spans categories - it is not the promotional pill`() =
        runBlocking<Unit> {
            seed()
            val promos = load(dao.pagingInbox(category = Category.PROMOTIONAL, unreadOnly = false, scamOnly = false))
            val flagged = load(dao.pagingInbox(category = null, unreadOnly = false, scamOnly = true))
            assertThat(promos).containsExactly(2L, 1L)
            assertThat(flagged).contains(3L)
            assertThat(flagged).doesNotContain(2L)
        }

    @Test
    fun `scam pill composes with the unread toggle`() =
        runBlocking<Unit> {
            seed()
            val unreadFlagged = load(dao.pagingInbox(category = null, unreadOnly = true, scamOnly = true))
            assertThat(unreadFlagged).containsExactly(3L)
        }

    @Test
    fun `scamOnly off leaves the query untouched`() =
        runBlocking<Unit> {
            seed()
            val all = load(dao.pagingInbox(category = null, unreadOnly = false, scamOnly = false))
            assertThat(all).containsExactly(5L, 4L, 3L, 2L, 1L)
        }

    @Test
    fun `select-all under the scam pill targets the same flagged set`() =
        runBlocking<Unit> {
            seed()
            assertThat(dao.inboxThreadIds(category = null, unreadOnly = false, scamOnly = true))
                .containsExactly(3L, 1L)
            assertThat(dao.inboxThreadIds(category = null, unreadOnly = true, scamOnly = true))
                .containsExactly(3L)
        }
}
