package app.clearsms.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE accountNumber = :accountNumber ORDER BY timestamp DESC")
    fun observeByAccount(accountNumber: String): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE rawSmsId = :rawSmsId LIMIT 1")
    suspend fun findByRawSmsId(rawSmsId: Long): TransactionEntity?

    @Query("SELECT * FROM transactions ORDER BY id ASC")
    suspend fun getAll(): List<TransactionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(transactions: List<TransactionEntity>)

    @Query("UPDATE transactions SET note = :note WHERE id = :transactionId")
    suspend fun setNote(
        transactionId: Long,
        note: String?,
    )

    @Query("DELETE FROM transactions WHERE rawSmsId = :rawSmsId")
    suspend fun deleteByRawSmsId(rawSmsId: Long)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()
}
