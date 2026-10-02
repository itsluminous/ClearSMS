package app.clearsms.data.db

import android.content.Context
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
 * The SQL half of the launcher-shortcut exclusion set (issue #81): what
 * [MessageDao.shortcutCandidates] returns and in which order, and what
 * [MessageDao.shortcutCandidateForThread] says about one thread once its
 * state changes under a shortcut the user pinned. A Spam, blocked, archived
 * or fully-binned thread never comes back; a thread binned or re-sorted to
 * Spam AFTER publishing drops out on the next read - the Room flow this
 * backs is what removes the stale shortcut. All fixtures are synthetic.
 */
@RunWith(RobolectricTestRunner::class)
class ShortcutCandidatesDaoTest {
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
        threadId: Long,
        timestamp: Long,
        category: Category = Category.PERSONAL,
        archived: Boolean = false,
        deletedAt: Long? = null,
        blocked: Boolean = false,
    ) = MessageEntity(
        id = id,
        threadId = threadId,
        sender = "sender-$threadId",
        normalizedSender = "sender-$threadId",
        body = "body $id",
        timestamp = timestamp,
        isArchived = archived,
        deletedAt = deletedAt,
        isBlockedSender = blocked,
        category = category,
    )

    private fun candidates(limit: Int = 10) = runBlocking { dao.shortcutCandidates(limit).first() }

    @Test
    fun `candidates are pinned first then newest, one row per thread, within the limit`() =
        runBlocking<Unit> {
            dao.insertAll(
                listOf(
                    message(1, threadId = 1, timestamp = 100),
                    message(2, threadId = 1, timestamp = 900),
                    message(3, threadId = 2, timestamp = 500),
                    message(4, threadId = 3, timestamp = 300),
                    message(5, threadId = 4, timestamp = 700),
                ),
            )
            db.threadPinDao().upsertAll(listOf(ThreadPinEntity("sender-3", pinnedAt = 1L)))

            val all = candidates()
            assertThat(all.map { it.threadId }).containsExactly(3L, 1L, 4L, 2L).inOrder()
            assertThat(all.first().pinned).isTrue()
            assertThat(all.drop(1).none { it.pinned }).isTrue()
            // The representative is the thread's newest message.
            assertThat(all.single { it.threadId == 1L }.timestamp).isEqualTo(900L)
            assertThat(candidates(limit = 2).map { it.threadId }).containsExactly(3L, 1L).inOrder()
        }

    @Test
    fun `Spam, blocked, archived and fully binned threads are never candidates`() =
        runBlocking<Unit> {
            dao.insertAll(
                listOf(
                    message(1, threadId = 1, timestamp = 100, category = Category.SPAM),
                    message(2, threadId = 2, timestamp = 200, blocked = true),
                    message(3, threadId = 3, timestamp = 300, archived = true),
                    message(4, threadId = 4, timestamp = 400, deletedAt = 1L),
                    message(5, threadId = 5, timestamp = 500),
                ),
            )
            assertThat(candidates().map { it.threadId }).containsExactly(5L)
        }

    @Test
    fun `the exclusion follows the NEWEST live message - a thread re-sorted to Spam drops out`() =
        runBlocking<Unit> {
            dao.insertAll(
                listOf(
                    message(1, threadId = 1, timestamp = 100),
                    message(2, threadId = 1, timestamp = 200, category = Category.SPAM),
                    message(3, threadId = 2, timestamp = 150, category = Category.SPAM),
                    message(4, threadId = 2, timestamp = 250),
                ),
            )
            // Thread 1's newest message is Spam: excluded. Thread 2's newest
            // is not, even though an older one was: a candidate.
            assertThat(candidates().map { it.threadId }).containsExactly(2L)
        }

    @Test
    fun `binning every live message after publishing removes the thread and so its shortcut`() =
        runBlocking<Unit> {
            dao.insertAll(listOf(message(1, threadId = 1, timestamp = 100), message(2, threadId = 1, timestamp = 200)))
            assertThat(candidates().map { it.threadId }).containsExactly(1L)
            assertThat(dao.shortcutCandidateForThread(1L)).isNotNull()

            dao.stageDelete(listOf(1L, 2L), deletedAt = 5L)
            assertThat(candidates()).isEmpty()
            // The pinned-shortcut re-check sees nothing live: null -> excluded.
            assertThat(dao.shortcutCandidateForThread(1L)).isNull()

            // Binning only the newest leaves the older one as representative.
            dao.undoDelete(listOf(1L))
            assertThat(dao.shortcutCandidateForThread(1L)?.timestamp).isEqualTo(100L)
        }

    @Test
    fun `blocking after publishing is visible to the pinned-shortcut re-check`() =
        runBlocking<Unit> {
            dao.insertAll(listOf(message(1, threadId = 7, timestamp = 100)))
            assertThat(dao.shortcutCandidateForThread(7L)?.isBlockedSender).isFalse()
            dao.setBlockedSender("sender-7", blocked = true)
            assertThat(dao.shortcutCandidateForThread(7L)?.isBlockedSender).isTrue()
            assertThat(candidates()).isEmpty()
        }

    @Test
    fun `an archived thread is not a candidate even for the per-thread re-check`() =
        runBlocking<Unit> {
            dao.insertAll(listOf(message(1, threadId = 9, timestamp = 100, archived = true)))
            assertThat(dao.shortcutCandidateForThread(9L)).isNull()
        }
}
