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
)
