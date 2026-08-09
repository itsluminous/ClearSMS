package app.clearsms.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SubCategory

/** A single SMS message with its categorization and extracted metadata. */
@Entity(
    tableName = "messages",
    indices = [
        Index("threadId"),
        Index("normalizedSender"),
        Index("category"),
        Index("timestamp"),
        // Serves the paged conversation query (WHERE threadId ORDER BY
        // timestamp DESC) without a separate sort step on large threads.
        Index("threadId", "timestamp"),
        // Idempotency key for the history import: re-processing the same
        // system provider row can never create a duplicate. NULL (messages
        // that arrived live through SMS_DELIVER) is exempt from uniqueness.
        Index("systemSmsId", unique = true),
        // Serves the soft-delete filters (deletedAt IS NULL on every read
        // path) and the recycle-bin listing/purge queries.
        Index("deletedAt"),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val threadId: Long,
    val sender: String,
    val normalizedSender: String,
    val body: String,
    val timestamp: Long,
    @ColumnInfo(defaultValue = "0") val isRead: Boolean = false,
    @ColumnInfo(defaultValue = "0") val isArchived: Boolean = false,
    val category: Category,
    val subCategory: SubCategory? = null,
    val extractedOtp: String? = null,
    val extractedDataJson: String? = null,
    @ColumnInfo(defaultValue = "0") val isBlockedSender: Boolean = false,
    /** `_id` of the originating row in the system SMS provider, when imported. */
    val systemSmsId: Long? = null,
    /**
     * True for messages the user sent, false for received ones. A boolean
     * (not an enum) because SMS has exactly two directions, and the SQL
     * `DEFAULT 0` makes every pre-upgrade or unmatched row incoming — the
     * safe reading — without a converter. Drives bubble alignment.
     */
    @ColumnInfo(defaultValue = "0") val isOutgoing: Boolean = false,
    /** Send lifecycle for outgoing messages; always null on incoming rows. */
    val deliveryStatus: DeliveryStatus? = null,
    /**
     * Soft-delete marker: null for live messages; the deletion timestamp for
     * messages the user deleted. A non-null value hides the row from every
     * read path (inbox, conversation, search, counts) — first for the
     * transient undo window, and after commit as the recycle-bin resting
     * state (when the bin is enabled). Also the 30-day purge clock.
     */
    val deletedAt: Long? = null,
    /**
     * True while a soft-deleted row's system-provider deletion has NOT been
     * committed yet (the Gmail-style undo window). The provider deletion is
     * deferred so undo never has to re-insert a provider row; a pending flag
     * that survives process death is committed on next launch, so a deleted
     * message can never resurrect in other SMS apps.
     */
    @ColumnInfo(defaultValue = "0") val providerDeletePending: Boolean = false,
)
