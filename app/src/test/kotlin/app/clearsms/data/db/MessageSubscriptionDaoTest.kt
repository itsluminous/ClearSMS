package app.clearsms.data.db

import android.content.Context
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

/** SIM bookkeeping on message rows: recording, thread recall and corpus span. */
@RunWith(RobolectricTestRunner::class)
class MessageSubscriptionDaoTest {
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
        threadId: Long = 1L,
        timestamp: Long,
        subscriptionId: Int? = null,
    ) = MessageEntity(
        threadId = threadId,
        sender = "+15551234567",
        normalizedSender = "5551234567",
        body = "hello",
        timestamp = timestamp,
        category = Category.PERSONAL,
        subscriptionId = subscriptionId,
    )

    @Test
    fun `an incoming message's receiving SIM is recorded on its row`() =
        runBlocking<Unit> {
            val id = dao.insert(message(timestamp = 100))

            dao.setSubscriptionId(id, 7)

            assertThat(dao.getById(id)?.subscriptionId).isEqualTo(7)
        }

    @Test
    fun `the thread's last-used SIM is the newest row that recorded one`() =
        runBlocking<Unit> {
            dao.insert(message(timestamp = 100, subscriptionId = 3))
            dao.insert(message(timestamp = 200, subscriptionId = 7))
            // Newest message carries no SIM - it must not mask the history.
            dao.insert(message(timestamp = 300, subscriptionId = null))

            assertThat(dao.lastSubscriptionIdInThread(1L)).isEqualTo(7)
        }

    @Test
    fun `distinct subscription ids span the whole corpus`() =
        runBlocking<Unit> {
            dao.insert(message(threadId = 1L, timestamp = 100, subscriptionId = 3))
            dao.insert(message(threadId = 2L, timestamp = 200, subscriptionId = 7))
            dao.insert(message(threadId = 3L, timestamp = 300, subscriptionId = 7))
            dao.insert(message(threadId = 4L, timestamp = 400, subscriptionId = null))

            assertThat(dao.distinctSubscriptionIds()).containsExactly(3, 7)
        }
}
