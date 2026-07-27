package app.clearsms.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders WHERE dueDate IS NULL OR dueDate >= :nowMs ORDER BY dueDate ASC")
    fun observeUpcoming(nowMs: Long): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE dueDate IS NOT NULL AND dueDate < :nowMs ORDER BY dueDate DESC")
    fun observePast(nowMs: Long): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE rawSmsId = :rawSmsId LIMIT 1")
    suspend fun findByRawSmsId(rawSmsId: Long): ReminderEntity?

    @Query("SELECT * FROM reminders ORDER BY id ASC")
    suspend fun getAll(): List<ReminderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reminder: ReminderEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(reminders: List<ReminderEntity>)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Drops the reminder derived from a message, so it can be re-derived. */
    @Query("DELETE FROM reminders WHERE rawSmsId = :rawSmsId")
    suspend fun deleteByRawSmsId(rawSmsId: Long)

    @Query("DELETE FROM reminders")
    suspend fun deleteAll()
}
