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
 * The "Spam" pill is a plain CATEGORY filter ([Category.SPAM]) like every
 * other pill - it does NOT widen to scam-FLAGGED messages of other
 * categories. The flag ([SubCategory.SCAM]) is a warning that rides on top
 * of whatever category the message has: a scam-flagged bank alert stays
 * under Important (with its warning), and a spam message that is not
 * flagged still shows under Spam. Fixtures are synthetic.
 */
@RunWith(RobolectricTestRunner::class)
class SpamPillQueryTest {
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
                // Heuristic scam hit: SPAM + SCAM flag.
                message(1, threadId = 1, category = Category.SPAM, subCategory = SubCategory.SCAM),
                // Plain promotion - not spam.
                message(2, threadId = 2, category = Category.PROMOTIONAL, subCategory = SubCategory.OFFER),
                // A `scam` rule can flag any category: IMPORTANT + SCAM stays Important.
                message(3, threadId = 3, category = Category.IMPORTANT, subCategory = SubCategory.SCAM),
                // A user "always sort this sender as Spam" rule: SPAM, no flag, unread.
                message(4, threadId = 4, category = Category.SPAM, isRead = false),
                // A thread whose OLDER message was spam but whose latest is not:
                // the inbox row is the latest message, so it is not in the set.
                message(5, threadId = 5, category = Category.SPAM),
                message(6, threadId = 5, category = Category.PERSONAL),
            ),
        )
    }

    @Test
    fun `spam pill returns exactly the threads whose latest message is SPAM`() =
        runBlocking<Unit> {
            seed()
            val spam = load(dao.pagingInbox(category = Category.SPAM, unreadOnly = false))
            assertThat(spam).containsExactly(4L, 1L)
        }

    @Test
    fun `a scam-flagged message of another category is NOT under the spam pill`() =
        runBlocking<Unit> {
            seed()
            val spam = load(dao.pagingInbox(category = Category.SPAM, unreadOnly = false))
            val important = load(dao.pagingInbox(category = Category.IMPORTANT, unreadOnly = false))
            assertThat(spam).doesNotContain(3L)
            assertThat(important).containsExactly(3L)
        }

    @Test
    fun `spam is not promotional and vice versa`() =
        runBlocking<Unit> {
            seed()
            val promos = load(dao.pagingInbox(category = Category.PROMOTIONAL, unreadOnly = false))
            assertThat(promos).containsExactly(2L)
        }

    @Test
    fun `spam pill composes with the unread toggle`() =
        runBlocking<Unit> {
            seed()
            val unreadSpam = load(dao.pagingInbox(category = Category.SPAM, unreadOnly = true))
            assertThat(unreadSpam).containsExactly(4L)
        }

    @Test
    fun `the all view still lists every thread once`() =
        runBlocking<Unit> {
            seed()
            val all = load(dao.pagingInbox(category = null, unreadOnly = false))
            assertThat(all).containsExactly(5L, 4L, 3L, 2L, 1L)
        }

    @Test
    fun `select-all under the spam pill targets the same set`() =
        runBlocking<Unit> {
            seed()
            assertThat(dao.inboxThreadIds(category = Category.SPAM, unreadOnly = false))
                .containsExactly(4L, 1L)
            assertThat(dao.inboxThreadIds(category = Category.SPAM, unreadOnly = true))
                .containsExactly(4L)
        }
}
