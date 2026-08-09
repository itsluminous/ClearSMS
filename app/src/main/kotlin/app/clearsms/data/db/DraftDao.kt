package app.clearsms.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DraftDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: DraftEntity)

    @Query("SELECT * FROM drafts WHERE threadId = :threadId")
    suspend fun forThread(threadId: Long): DraftEntity?

    @Query("DELETE FROM drafts WHERE threadId = :threadId")
    suspend fun delete(threadId: Long)
}
