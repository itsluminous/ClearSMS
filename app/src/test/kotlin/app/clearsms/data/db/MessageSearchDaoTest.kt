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

/** FTS-backed search: matching, filter composition and paging behaviour. */
@RunWith(RobolectricTestRunner::class)
class MessageSearchDaoTest {
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
        body: String,
        sender: String = "AX-TEST",
        timestamp: Long = id,
        category: Category = Category.PERSONAL,
    ) = MessageEntity(
        id = id,
        threadId = id,
        sender = sender,
        normalizedSender = sender,
        body = body,
        timestamp = timestamp,
        category = category,
    )

    private suspend fun load(
        source: PagingSource<Int, MessageEntity>,
        loadSize: Int = 50,
    ): List<MessageEntity> {
        val result =
            source.load(
                PagingSource.LoadParams.Refresh(key = null, loadSize = loadSize, placeholdersEnabled = false),
            )
        return (result as PagingSource.LoadResult.Page).data
    }

    @Test
    fun `prefix match finds body and sender tokens`() =
        runBlocking<Unit> {
            dao.insert(message(1, "Salary credited to your account"))
            dao.insert(message(2, "Recharge successful"))
            dao.insert(message(3, "hello", sender = "VM-SALDEP"))

            val hits = load(dao.pagingSearch("sal*", category = null, cutoffMs = null))

            assertThat(hits.map { it.id }).containsExactly(1L, 3L)
        }

    @Test
    fun `infix substrings do not match — prefix semantics`() =
        runBlocking<Unit> {
            dao.insert(message(1, "Salary credited"))

            assertThat(load(dao.pagingSearch("alary*", null, null))).isEmpty()
        }

    @Test
    fun `category and cutoff filters compose with the match`() =
        runBlocking<Unit> {
            dao.insert(message(1, "Salary credited", category = Category.IMPORTANT, timestamp = 100))
            dao.insert(message(2, "Salary credited", category = Category.PROMOTIONAL, timestamp = 100))
            dao.insert(message(3, "Salary credited", category = Category.IMPORTANT, timestamp = 10))

            val hits = load(dao.pagingSearch("salary*", Category.IMPORTANT, cutoffMs = 50))

            assertThat(hits.map { it.id }).containsExactly(1L)
        }

    @Test
    fun `results come newest first and page incrementally`() =
        runBlocking<Unit> {
            for (i in 1L..10L) dao.insert(message(i, "salary $i", timestamp = i))

            val page = load(dao.pagingSearch("salary*", null, null), loadSize = 4)

            assertThat(page.map { it.id }).containsExactly(10L, 9L, 8L, 7L).inOrder()
        }

    @Test
    fun `multi-token match requires all tokens`() =
        runBlocking<Unit> {
            dao.insert(message(1, "Salary credited to account"))
            dao.insert(message(2, "Salary debited from account"))

            val hits = load(dao.pagingSearch("salary* cred*", null, null))

            assertThat(hits.map { it.id }).containsExactly(1L)
        }

    @Test
    fun `index stays in sync through update and delete`() =
        runBlocking<Unit> {
            dao.insert(message(1, "Salary credited"))
            dao.update(message(1, "Refund processed"))

            assertThat(load(dao.pagingSearch("salary*", null, null))).isEmpty()
            assertThat(load(dao.pagingSearch("refund*", null, null))).hasSize(1)

            dao.deleteById(1)
            assertThat(load(dao.pagingSearch("refund*", null, null))).isEmpty()
        }

    @Test
    fun `non-paged search flow matches too`() =
        runBlocking<Unit> {
            dao.insert(message(1, "Salary credited"))

            assertThat(dao.search("sal*").first().map { it.id }).containsExactly(1L)
        }
}
