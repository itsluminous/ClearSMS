package app.clearsms.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates the Gmail-style transient undo for destructive message
 * actions (delete and archive), one pending action at a time.
 *
 * Delete uses the repository's deferred-commit design: the app database is
 * soft-deleted immediately (UI updates at once), while the system-provider
 * deletion is deferred until the undo window closes - undo then only flips
 * flags and never has to re-insert provider rows. A new destructive action
 * commits the previous pending one first, and any staged deletion that
 * outlives the process is committed by [onAppStart] on the next launch, so
 * deleted messages can never resurrect in other SMS apps.
 *
 * Archive undo is a trivial flag revert; committing an archive is a no-op.
 *
 * Bulk/background deletions (OTP auto-delete, "Clear older OTPs") do NOT go
 * through this manager - they keep committing immediately.
 */
class UndoManager(
    private val repository: MessageRepository,
    private val scope: CoroutineScope,
    /** Reads Settings → Messages → Recycle bin at commit time. */
    private val recycleBinEnabled: suspend () -> Boolean,
    private val undoWindowMs: Long = UNDO_WINDOW_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private sealed interface Pending {
        /** Staged (soft-deleted) message ids awaiting the provider commit. */
        data class Delete(
            val messageIds: List<Long>,
        ) : Pending

        /** Archived units to revert: whole threads and/or single messages. */
        data class Archive(
            val threadIds: List<Long> = emptyList(),
            val messageIds: List<Long> = emptyList(),
        ) : Pending
    }

    private val mutex = Mutex()
    private var pending: Pending? = null
    private var timer: Job? = null

    /**
     * Immediate bin-aware delete for callers with no undo surface
     * (notification action buttons): stages and commits in one step, so the
     * recycle-bin setting is honored exactly like a timed-out UI delete.
     * Any pending UI undo is committed first (one pending action at a time).
     */
    suspend fun deleteNow(ids: List<Long>) {
        if (ids.isEmpty()) return
        commitPending()
        val staged = repository.stageDeleteMessages(ids)
        repository.commitStagedDelete(staged, toBin = recycleBinEnabled())
    }

    /** Stages [ids] for deletion; returns the number of messages staged. */
    suspend fun stageDeleteMessages(ids: List<Long>): Int =
        stage { Pending.Delete(repository.stageDeleteMessages(ids)) }
            .let { (it as Pending.Delete).messageIds.size }

    /** Stages whole threads for deletion; returns the number of threads. */
    suspend fun stageDeleteThreads(threadIds: List<Long>): Int {
        stage { Pending.Delete(repository.stageDeleteThreads(threadIds)) }
        return threadIds.size
    }

    /** Archives [threadIds] undoably; returns the number of threads. */
    suspend fun stageArchiveThreads(threadIds: List<Long>): Int {
        stage {
            repository.archiveThreads(threadIds, archived = true)
            Pending.Archive(threadIds = threadIds)
        }
        return threadIds.size
    }

    /** Archives a single message undoably (the inbox swipe action). */
    suspend fun stageArchiveMessage(messageId: Long) {
        stage {
            repository.archive(messageId, archived = true)
            Pending.Archive(messageIds = listOf(messageId))
        }
    }

    /** Reverts the pending action, if its window is still open. */
    suspend fun undo() {
        mutex.withLock {
            timer?.cancel()
            timer = null
            when (val action = pending.also { pending = null }) {
                is Pending.Delete -> repository.undoStagedDelete(action.messageIds)
                is Pending.Archive -> {
                    if (action.threadIds.isNotEmpty()) repository.archiveThreads(action.threadIds, archived = false)
                    action.messageIds.forEach { repository.archive(it, archived = false) }
                }
                null -> Unit
            }
        }
    }

    /** Closes the undo window now (snackbar dismissed, app backgrounded…). */
    suspend fun commitPending() {
        mutex.withLock { commitLocked() }
    }

    /**
     * Startup recovery + bin maintenance: commits any staged deletion that
     * survived process death, then purges bin rows past the 30-day
     * retention.
     */
    fun onAppStart() {
        scope.launch {
            repository.commitAllPendingDeletes(toBin = recycleBinEnabled())
            repository.purgeExpiredBin(clock() - BIN_RETENTION_MS)
        }
    }

    /** Commits the previous action, installs the new one, arms the timer. */
    private suspend fun stage(action: suspend () -> Pending): Pending =
        mutex.withLock {
            commitLocked()
            val staged = action()
            pending = staged
            timer =
                scope.launch {
                    delay(undoWindowMs)
                    // Identity-checked: a timer that lost the race against a
                    // newer stage/undo must never commit the newer action.
                    mutex.withLock { if (pending === staged) commitLocked() }
                }
            staged
        }

    private suspend fun commitLocked() {
        // Never self-cancel: when the expiring timer itself commits, its own
        // job must stay live through the suspend calls below.
        val armed = timer
        timer = null
        if (armed != null && armed !== currentCoroutineContext()[Job]) armed.cancel()
        when (val action = pending.also { pending = null }) {
            is Pending.Delete -> repository.commitStagedDelete(action.messageIds, toBin = recycleBinEnabled())
            // An archive is already fully applied; nothing is deferred.
            is Pending.Archive, null -> Unit
        }
    }

    companion object {
        /** Snackbar-length window, matching Gmail's transient undo. */
        const val UNDO_WINDOW_MS = 5_000L

        /** Fixed recycle-bin retention: 30 days, stated in the settings row. */
        const val BIN_RETENTION_MS = 30L * 24 * 60 * 60 * 1_000
    }
}
