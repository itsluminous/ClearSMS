package app.clearsms.data.db

import app.clearsms.domain.model.Category

/**
 * One thread as the launcher-shortcut publisher sees it: the facts of its
 * newest live message that decide whether the thread may become a shortcut
 * and where it ranks, and nothing else - no body, no extracted values. Read
 * through [MessageDao.shortcutCandidates] (the ranked over-sample) and
 * [MessageDao.shortcutCandidateForThread] (one pinned shortcut's thread).
 *
 * [threadId] is the app's thread identity - anchored on the platform's
 * `thread_id` since issue #42 ([app.clearsms.data.repository.ThreadIdentity])
 * and the same value every `clearsms://conversation/<threadId>` deep link
 * carries. Never a provider row id (`systemSmsId`), which the telephony
 * store recycles.
 */
data class ShortcutCandidateRow(
    val threadId: Long,
    val sender: String,
    val normalizedSender: String,
    /** Received time of the thread's newest live message - the recency key. */
    val timestamp: Long,
    val category: Category,
    /** Derived per-row block cache; the settings blocklist is re-checked too. */
    val isBlockedSender: Boolean,
    /** When the thread was pinned, or null when unpinned. */
    val pinnedAt: Long?,
) {
    val pinned: Boolean get() = pinnedAt != null
}
