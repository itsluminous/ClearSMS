package app.clearsms.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.clearsms.domain.model.ReminderType

/** A bill / payment reminder extracted from an SMS. */
@Entity(
    tableName = "reminders",
    indices = [Index("dueDate"), Index("rawSmsId"), Index("dismissedAt")],
)
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: ReminderType,
    /** Due date as epoch milliseconds (start of day), null when it could not be parsed. */
    val dueDate: Long? = null,
    val totalDue: Double? = null,
    val minDue: Double? = null,
    val accountLast4: String? = null,
    val bankName: String? = null,
    /** Free-form label: the order/tracking reference for DELIVERY reminders. */
    val label: String? = null,
    /** Row id of the originating message in the messages table. */
    val rawSmsId: Long,
    val createdAt: Long,
    /**
     * Dismissal marker: null for live alerts; the dismissal timestamp for
     * cards the user dismissed. A dismissed reminder rests in the Alerts
     * "Older" section (it is never hard-deleted by Dismiss), can be
     * restored from there, and is auto-purged after the Older retention
     * window - see [app.clearsms.data.repository.ReminderBucketing].
     */
    val dismissedAt: Long? = null,
)
