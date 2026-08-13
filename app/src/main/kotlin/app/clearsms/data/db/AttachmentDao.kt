package app.clearsms.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Metadata rows for stored MMS attachments (files live under filesDir/mms). */
@Dao
interface AttachmentDao {
    @Insert
    suspend fun insertAll(attachments: List<AttachmentEntity>): List<Long>

    @Query("SELECT * FROM attachments WHERE messageId = :messageId ORDER BY id ASC")
    suspend fun forMessage(messageId: Long): List<AttachmentEntity>

    /**
     * Every attachment of the thread's live messages, for the conversation
     * screen to key by message id. Threads carry few attachments, so one
     * observed list beats a per-bubble query.
     */
    @Query(
        "SELECT a.* FROM attachments a INNER JOIN messages m ON m.id = a.messageId " +
            "WHERE m.threadId = :threadId AND m.deletedAt IS NULL ORDER BY a.id ASC",
    )
    fun observeForThread(threadId: Long): Flow<List<AttachmentEntity>>

    @Query("DELETE FROM attachments WHERE messageId IN (:messageIds)")
    suspend fun deleteForMessages(messageIds: List<Long>)
}
