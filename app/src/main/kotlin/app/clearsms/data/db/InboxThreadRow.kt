package app.clearsms.data.db

import androidx.room.Embedded

/**
 * One inbox row: the thread's latest message plus the per-thread decorations
 * joined in SQL, so Room's invalidation tracker refreshes the paged inbox
 * when a decoration changes (a Kotlin-side combine over a separate flow
 * would not re-render already-loaded pages).
 */
data class InboxThreadRow(
    @Embedded val message: MessageEntity,
    /** Unsent compose text of the thread, or null when it has no draft. */
    val draftText: String?,
)
