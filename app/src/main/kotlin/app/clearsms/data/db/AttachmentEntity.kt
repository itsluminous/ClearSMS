package app.clearsms.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One stored MMS attachment. The bytes live as an app-private file at
 * `filesDir/mms/<messageId>/<fileName>` (see
 * [app.clearsms.mms.AttachmentStore]); this row is the queryable metadata.
 * Deleting a message deletes its attachment rows and files together.
 */
@Entity(
    tableName = "attachments",
    indices = [Index("messageId")],
)
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Owning row in `messages`. */
    val messageId: Long,
    /** Lower-cased mime type as declared by the part, e.g. `image/jpeg`. */
    val mimeType: String,
    /** File name under the message's attachment directory (also display name). */
    val fileName: String,
    val sizeBytes: Long,
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
}
