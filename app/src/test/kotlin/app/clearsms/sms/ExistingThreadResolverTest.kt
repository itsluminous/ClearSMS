package app.clearsms.sms

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.MessageDao
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.repository.SenderNormalizer
import app.clearsms.domain.model.Category
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Which existing conversation a composer recipient is routed to (issue
 * #119): the thread a message to them WOULD JOIN, by the #42 identity -
 * the platform's thread first, the sender key second - and only while it
 * has a live message.
 */
@RunWith(RobolectricTestRunner::class)
class ExistingThreadResolverTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var dao: MessageDao
    private var providerThreadId: Long? = null

    @Before
    fun setUp() {
        SenderNormalizer.defaultRegion = null
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

    private fun resolver() = ExistingThreadResolver(dao, { providerThreadId }, Dispatchers.Unconfined)

    private suspend fun row(
        sender: String,
        threadId: Long,
        providerThreadId: Long? = null,
        deletedAt: Long? = null,
    ) = dao.insert(
        MessageEntity(
            threadId = threadId,
            sender = sender,
            normalizedSender = SenderNormalizer.normalize(sender),
            body = "earlier",
            timestamp = 1_000L,
            isRead = true,
            category = Category.PERSONAL,
            providerThreadId = providerThreadId,
            deletedAt = deletedAt,
        ),
    )

    @Test
    fun `the platform's thread is the primary key - a dialling variant the sender key misses is still routed home`() =
        runBlocking {
            row("+48601234567", threadId = 9L, providerThreadId = 77L)
            providerThreadId = 77L

            // "0601234567" keys to a different string than "601234567"...
            assertThat(dao.threadIdFor(SenderNormalizer.normalize("0601234567"))).isNull()
            // ...yet the provider says it is the same conversation.
            assertThat(resolver().threadFor("0601234567")).isEqualTo(9L)
        }

    @Test
    fun `without a platform answer the sender key is the fallback - exactly as an insert would resolve`() =
        runBlocking {
            row("+15550001234", threadId = 4L)
            providerThreadId = null

            assertThat(resolver().threadFor("+1 555 000 1234")).isEqualTo(4L)
            assertThat(resolver().threadFor("0601234567")).isNull()
        }

    @Test
    fun `a platform thread holding a DIFFERENT person is ignored - the provider may merge variants, never people`() =
        runBlocking {
            row("+15550001234", threadId = 4L, providerThreadId = 77L)
            providerThreadId = 77L

            assertThat(resolver().threadFor("+15559998888")).isNull()
        }

    @Test
    fun `no history, a binned-whole thread, or a blank address resolve to nothing`() =
        runBlocking {
            row("+15550002222", threadId = 6L, deletedAt = 123L)

            assertThat(resolver().threadFor("+15550002222")).isNull()
            assertThat(resolver().threadFor("+15550003333")).isNull()
            assertThat(resolver().threadFor("   ")).isNull()
        }
}
