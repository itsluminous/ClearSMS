package app.clearsms.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    /**
     * Every stored reminder, dismissed or not. Active/Older bucketing (age
     * windows, dismissal, dedup) happens at the read layer - see
     * [app.clearsms.data.repository.ReminderBucketing].
     */
    @Query("SELECT * FROM reminders")
    fun observeAll(): Flow<List<ReminderEntity>>

    /**
     * Dated, not-yet-due, not-dismissed reminders - the alarm scheduler's
     * input. Dismissed reminders never fire bill-due alarms.
     */
    @Query(
        "SELECT * FROM reminders WHERE dismissedAt IS NULL AND dueDate IS NOT NULL AND dueDate >= :nowMs ORDER BY dueDate ASC",
    )
    fun observeUpcoming(nowMs: Long): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE rawSmsId = :rawSmsId LIMIT 1")
    suspend fun findByRawSmsId(rawSmsId: Long): ReminderEntity?

    @Query("SELECT * FROM reminders ORDER BY id ASC")
    suspend fun getAll(): List<ReminderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reminder: ReminderEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(reminders: List<ReminderEntity>)

    /** Flags (or, with null, un-flags) rows as dismissed - never a delete. */
    @Query("UPDATE reminders SET dismissedAt = :dismissedAt WHERE id IN (:ids)")
    suspend fun setDismissed(
        ids: List<Long>,
        dismissedAt: Long?,
    )

    @Query("DELETE FROM reminders WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** Drops the reminder derived from a message, so it can be re-derived. */
    @Query("DELETE FROM reminders WHERE rawSmsId = :rawSmsId")
    suspend fun deleteByRawSmsId(rawSmsId: Long)

    @Query("DELETE FROM reminders")
    suspend fun deleteAll()
}
