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

    /** Newest transactions bounded by [limit] — backs the growing "load more" page. */
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE accountNumber = :accountNumber ORDER BY timestamp DESC")
    fun observeByAccount(accountNumber: String): Flow<List<TransactionEntity>>

    /** Newest transactions for one account bounded by [limit] — account-detail "load more" page. */
    @Query("SELECT * FROM transactions WHERE accountNumber = :accountNumber ORDER BY timestamp DESC LIMIT :limit")
    fun observeByAccountLimited(
        accountNumber: String,
        limit: Int,
    ): Flow<List<TransactionEntity>>

    /** Most recent transaction for an account+bank — the message behind the latest balance update. */
    @Query(
        """
        SELECT * FROM transactions
        WHERE accountNumber = :accountNumber AND bankName = :bankName
        ORDER BY timestamp DESC LIMIT 1
        """,
    )
    suspend fun latestForAccount(
        accountNumber: String,
        bankName: String,
    ): TransactionEntity?

    /** Fallback: most recent transaction matched on account number alone. */
    @Query("SELECT * FROM transactions WHERE accountNumber = :accountNumber ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestForAccountNumber(accountNumber: String): TransactionEntity?

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
