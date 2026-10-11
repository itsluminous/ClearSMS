package app.clearsms.testing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.room.RoomDatabase
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Owns what a ViewModel test builds against an in-memory Room database -
 * the view models and the executor Room runs their queries on - so teardown
 * happens in the ONE order that cannot leak a database call past `close()`.
 *
 * WHY: a view model's coroutines live in `viewModelScope`, which only the
 * framework cancels (when the screen's owner is destroyed). A test that
 * builds the view model by hand and then `db.close()`s in `@After` leaves
 * that scope ALIVE, and Room runs suspending DAO calls on its own executor
 * threads - so a still-running send watcher, paging source or `stateIn`
 * collector can execute its query AFTER the database is gone. The result is
 * `IllegalStateException: ... connection pool has been closed` escaping the
 * scope (it has no handler) as an uncaught coroutine exception, which
 * kotlinx-coroutines-test pins on whichever unrelated `runTest` runs next
 * (`UncaughtExceptionsBeforeTest`). Whether the pool thread or the test
 * thread wins is scheduling: green locally, red on a loaded CI runner
 * (PR #108, `SentMessageWatcherTest` the innocent victim of
 * `ConversationViewModelDelayedSendTest`'s immediate-send case).
 *
 * THE ORDER, enforced by [close]:
 * 1. clear the view models - `viewModelScope` is cancelled exactly as on a
 *    destroyed screen, so queued database work finds a cancelled job and
 *    never runs its query;
 * 2. drain the executor - anything already executing finishes while the
 *    database is still open, and nothing can be scheduled after;
 * 3. close the database.
 *
 * Usage: `fixture.onDatabase(Room.inMemoryDatabaseBuilder(...)).build()`,
 * `fixture.own(viewModel)` for every view model built, `fixture.close(db)`
 * in `@After` instead of `db.close()`. ViewModelTeardownConventionTest pins
 * this for every test that builds a view model against an in-memory
 * database; ConversationViewModelTeardownTest proves the order matters.
 */
class ViewModelDatabaseFixture(
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
) {
    private val store = ViewModelStore()
    private var owned = 0

    /** Points Room's query AND transaction work at the fixture's executor. */
    fun <T : RoomDatabase> onDatabase(builder: RoomDatabase.Builder<T>): RoomDatabase.Builder<T> =
        builder
            .setQueryExecutor(executor)
            .setTransactionExecutor(executor)

    /** Registers [viewModel] to be cleared by [close], and returns it. */
    fun <T : ViewModel> own(viewModel: T): T {
        store.put("owned-${owned++}", viewModel)
        return viewModel
    }

    /** Clears the owned view models, drains the executor, then closes [db]. */
    fun close(db: RoomDatabase) {
        store.clear()
        executor.shutdown()
        check(executor.awaitTermination(30, TimeUnit.SECONDS)) {
            "Room's executor still had work 30 s after the view models were cleared"
        }
        db.close()
    }
}
