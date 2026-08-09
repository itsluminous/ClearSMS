package app.clearsms.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Unsent compose text for one conversation, saved when the user leaves the
 * thread with text still in the reply field and restored on return.
 *
 * A separate table (not a column on message rows) because a draft belongs to
 * the THREAD, which is a derived grouping - no thread entity exists to hang a
 * column on, and stamping the latest message would silently move the draft
 * whenever a new message arrives. One row per thread: sending, scheduling or
 * clearing the text deletes the row, so a thread either has exactly one
 * draft or none.
 */
@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey val threadId: Long,
    val text: String,
    /** When the draft was last edited (not currently surfaced; audit only). */
    val updatedAt: Long,
)
