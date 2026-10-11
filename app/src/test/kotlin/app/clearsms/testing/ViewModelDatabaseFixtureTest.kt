package app.clearsms.testing

import androidx.lifecycle.ViewModel
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The teardown contract itself, tested without racing anything.
 *
 * CI once failed with `UncaughtExceptionsBeforeTest` in an innocent test
 * because a `ConversationViewModel` delivery watcher was still observing
 * `observeDeliveryStatus` when a previous test closed its in-memory
 * database: Room ran the query on its own executor, hit
 * `IllegalStateException: ... connection pool has been closed`, and the
 * exception escaped a scope with no handler, landing on whichever test ran
 * next.
 *
 * [ViewModelDatabaseFixture.close] is what makes that impossible, and the
 * order is the whole point. This test pins that order directly rather than
 * trying to re-stage the interleaving - a test that has to win a race to be
 * meaningful is the wrong shape for CI, which is where the original flake
 * came from.
 */
@RunWith(RobolectricTestRunner::class)
class ViewModelDatabaseFixtureTest {
    private fun database(fixture: ViewModelDatabaseFixture): ClearSmsDatabase =
        fixture
            .onDatabase(
                Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    ClearSmsDatabase::class.java,
                ),
            ).allowMainThreadQueries()
            .build()
            // Room opens lazily, so isOpen() is false until something uses
            // it; these tests are about open-vs-closed, so open it now.
            .also { it.openHelper.writableDatabase }

    private class Spy : ViewModel() {
        val cleared = AtomicBoolean(false)

        override fun onCleared() {
            cleared.set(true)
        }
    }

    @Test
    fun `close clears owned view models before the database is closed`() {
        val fixture = ViewModelDatabaseFixture()
        val db = database(fixture)
        val spy = fixture.own(Spy())

        assertWithMessage("not cleared until close()").that(spy.cleared.get()).isFalse()
        assertThat(db.isOpen).isTrue()

        fixture.close(db)

        assertWithMessage("the view model's scope is cancelled by close()").that(spy.cleared.get()).isTrue()
        assertWithMessage("and only then is the database closed").that(db.isOpen).isFalse()
    }

    @Test
    fun `close drains work already queued on Room's executor before closing`() {
        val fixture = ViewModelDatabaseFixture()
        val db = database(fixture)
        val started = CountDownLatch(1)
        val finishedWhileOpen = AtomicBoolean(false)

        // Queued the way Room queues a suspending DAO call: if close() shut
        // the database first, this would observe it already closed - which is
        // exactly the exception CI saw.
        db.queryExecutor.execute {
            started.countDown()
            Thread.sleep(150)
            finishedWhileOpen.set(db.isOpen)
        }
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()

        fixture.close(db)

        assertWithMessage("queued database work ran to completion while the database was still open")
            .that(finishedWhileOpen.get())
            .isTrue()
        assertThat(db.isOpen).isFalse()
    }

    @Test
    fun `a view model registered with own is returned unchanged`() {
        val fixture = ViewModelDatabaseFixture()
        val db = database(fixture)
        val spy = Spy()

        assertThat(fixture.own(spy)).isSameInstanceAs(spy)

        fixture.close(db)
    }

    @Test
    fun `close is safe when nothing was ever registered`() {
        val fixture = ViewModelDatabaseFixture()
        val db = database(fixture)

        fixture.close(db)

        assertThat(db.isOpen).isFalse()
    }
}
