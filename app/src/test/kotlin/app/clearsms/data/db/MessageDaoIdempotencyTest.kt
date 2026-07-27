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

@RunWith(RobolectricTestRunner::class)
class MessageDaoIdempotencyTest {
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
        systemSmsId: Long?,
        body: String = "hello",
    ) = MessageEntity(
        threadId = 1L,
        sender = "9876543210",
        normalizedSender = "9876543210",
        body = body,
        timestamp = 1_000L,
        category = Category.PERSONAL,
        systemSmsId = systemSmsId,
    )

    @Test
    fun `duplicate systemSmsId is ignored and reported as -1`() =
        runBlocking {
            val first = dao.insertAllIgnore(listOf(message(systemSmsId = 7L)))
            val second = dao.insertAllIgnore(listOf(message(systemSmsId = 7L, body = "same system row again")))

            assertThat(first.single()).isGreaterThan(0L)
            assertThat(second.single()).isEqualTo(-1L)
            assertThat(dao.getAll()).hasSize(1)
            assertThat(dao.getAll().single().body).isEqualTo("hello")
        }

    @Test
    fun `distinct systemSmsIds all insert`() =
        runBlocking {
            val ids = dao.insertAllIgnore(listOf(message(1L), message(2L), message(3L)))
            assertThat(ids).doesNotContain(-1L)
            assertThat(dao.getAll()).hasSize(3)
        }

    @Test
    fun `live messages without systemSmsId are never deduplicated`() =
        runBlocking {
            val ids = dao.insertAllIgnore(listOf(message(null), message(null)))
            assertThat(ids).doesNotContain(-1L)
            assertThat(dao.getAll()).hasSize(2)
        }
}
