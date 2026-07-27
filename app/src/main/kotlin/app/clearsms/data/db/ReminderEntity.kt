package app.clearsms.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.clearsms.domain.model.ReminderType

/** A bill / payment reminder extracted from an SMS. */
@Entity(
    tableName = "reminders",
    indices = [Index("dueDate"), Index("rawSmsId")],
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
)
