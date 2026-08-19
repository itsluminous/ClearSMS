package app.clearsms.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.db.isSameMessageAs
import app.clearsms.domain.model.Category
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Provider row ids are REUSABLE: the telephony store's `_id` is a plain
 * INTEGER PRIMARY KEY, so deleting the newest message hands its id to the
 * next insert. The app used to treat `systemSmsId` as permanently unique
 * (unique index + "same id means same message" dedup), which lost messages:
 *
 * - binning a message deletes its provider copy but kept the dangling id, so
 *   the next arrival reusing that id was discarded as a duplicate - found
 *   live on the emulator as a blocked-then-unblocked sender's messages
 *   vanishing (present in the system provider, absent from the app);
 * - the catch-up importer skipped such rows silently, losing them for good.
 *
 * A row now releases its provider id when its provider copy is deleted, and
 * both ingest paths (plus the importer) verify identity by body+timestamp
 * before believing a provider id.
 */
@RunWith(RobolectricTestRunner::class)
class ProviderIdReuseTest {
    private lateinit var db: ClearSmsDatabase

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() = db.close()

    private fun row(
        body: String,
        systemSmsId: Long?,
        timestamp: Long = 1_000L,
        deleted: Boolean = false,
    ) = MessageEntity(
        threadId = 1L,
        sender = "DOMINO",
        normalizedSender = "DOMINO",
        body = body,
        timestamp = timestamp,
        category = Category.PROMOTIONAL,
        systemSmsId = systemSmsId,
        deletedAt = if (deleted) timestamp else null,
        providerDeletePending = deleted,
    )

    @Test
    fun `committing a delete to the bin releases the provider id`() =
        runTest {
            val dao = db.messageDao()
            val id = dao.insert(row("binned message", systemSmsId = 42L, deleted = true))

            dao.clearProviderPendingAndSystemId(listOf(id))

            val stored = dao.getById(id)!!
            assertThat(stored.systemSmsId).isNull()
            assertThat(stored.deletedAt).isNotNull() // still in the bin
            assertThat(stored.providerDeletePending).isFalse()
        }

    @Test
    fun `a released id no longer blocks a new message that reuses it`() =
        runTest {
            val dao = db.messageDao()
            val binned = dao.insert(row("old binned", systemSmsId = 42L, deleted = true))
            dao.clearProviderPendingAndSystemId(listOf(binned))

            // The provider reuses id 42 for a brand-new message.
            val newId = dao.insertIgnore(row("brand new arrival", systemSmsId = 42L, timestamp = 2_000L))

            assertThat(newId).isNotEqualTo(-1L)
            assertThat(dao.bySystemSmsId(42L)!!.body).isEqualTo("brand new arrival")
        }

    @Test
    fun `a stale claim is detectable by content and can be released`() =
        runTest {
            val dao = db.messageDao()
            // A row that kept a dangling id (the pre-fix state, still on disk
            // for anyone who binned messages before this version).
            val stale = dao.insert(row("old binned", systemSmsId = 42L, deleted = true))
            assertThat(dao.insertIgnore(row("new arrival", systemSmsId = 42L, timestamp = 2_000L))).isEqualTo(-1L)

            val claimant = dao.bySystemSmsId(42L)!!
            assertThat(claimant.isSameMessageAs("new arrival", 2_000L)).isFalse()
            dao.clearSystemSmsId(claimant.id)

            val inserted = dao.insertIgnore(row("new arrival", systemSmsId = 42L, timestamp = 2_000L))
            assertThat(inserted).isNotEqualTo(-1L)
            assertThat(dao.getById(stale)!!.body).isEqualTo("old binned") // untouched
        }

    @Test
    fun `a genuine duplicate is still recognised as the same message`() =
        runTest {
            val dao = db.messageDao()
            dao.insert(row("same message", systemSmsId = 42L, timestamp = 5_000L))
            val claimant = dao.bySystemSmsId(42L)!!
            assertThat(claimant.isSameMessageAs("same message", 5_000L)).isTrue()
        }

    @Test
    fun `bulk lookup finds every claimant for a page of provider ids`() =
        runTest {
            val dao = db.messageDao()
            dao.insert(row("a", systemSmsId = 1L))
            dao.insert(row("b", systemSmsId = 2L))
            dao.insert(row("c", systemSmsId = null))

            val found = dao.bySystemSmsIds(listOf(1L, 2L, 3L))

            assertThat(found.map { it.body }).containsExactly("a", "b")
        }
}
