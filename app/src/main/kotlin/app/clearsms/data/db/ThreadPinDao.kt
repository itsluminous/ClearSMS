package app.clearsms.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ThreadPinDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(pins: List<ThreadPinEntity>)

    @Query("DELETE FROM thread_pins WHERE normalizedSender IN (:normalizedSenders)")
    suspend fun deleteBySenders(normalizedSenders: List<String>)

    @Query("SELECT COUNT(*) FROM thread_pins WHERE normalizedSender IN (:normalizedSenders)")
    suspend fun countBySenders(normalizedSenders: List<String>): Int

    @Query("SELECT * FROM thread_pins")
    suspend fun getAll(): List<ThreadPinEntity>

    @Query("DELETE FROM thread_pins")
    suspend fun deleteAll()
}
