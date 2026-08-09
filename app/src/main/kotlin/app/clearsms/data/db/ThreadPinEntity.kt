package app.clearsms.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A pinned conversation. Threads are a DERIVED grouping (messages carry a
 * threadId, but no thread row exists), and thread ids are reassigned when a
 * corpus is re-imported or restored - so the pin is keyed by the thread's
 * normalized sender, the one stable identity a conversation has. That is
 * also what lets pins survive a backup/restore of messages: the pin rides
 * the backup by sender and reattaches to whatever threadId the sender's
 * messages end up under.
 */
@Entity(tableName = "thread_pins")
data class ThreadPinEntity(
    @PrimaryKey val normalizedSender: String,
    /** When the pin was set; pinned threads still sort by message recency. */
    val pinnedAt: Long,
)
