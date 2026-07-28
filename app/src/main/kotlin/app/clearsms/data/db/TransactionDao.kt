package app.clearsms.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.clearsms.domain.model.TransactionType
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    /** Newest transactions bounded by [limit] — backs the growing "load more" page. */
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<TransactionEntity>>

    /**
     * Transactions belonging to ONE account: linked by [TransactionEntity.accountId],
     * with an exact (accountNumber, bankName) fallback for unlinked legacy
     * rows. Never matched on the last-4 alone — the same tail can exist at
     * several banks.
     */
    @Query(
        """
        SELECT t.* FROM transactions t
        WHERE t.accountId = (
            SELECT a.id FROM accounts a
            WHERE a.accountNumber = :accountNumber AND a.bankName = :bankName LIMIT 1
        )
        OR (t.accountId IS NULL AND t.accountNumber = :accountNumber AND t.bankName = :bankName)
        ORDER BY t.timestamp DESC
        """,
    )
    fun observeByAccount(
        accountNumber: String,
        bankName: String,
    ): Flow<List<TransactionEntity>>

    /** Newest transactions for one account bounded by [limit] — account-detail "load more" page. */
    @Query(
        """
        SELECT t.* FROM transactions t
        WHERE t.accountId = (
            SELECT a.id FROM accounts a
            WHERE a.accountNumber = :accountNumber AND a.bankName = :bankName LIMIT 1
        )
        OR (t.accountId IS NULL AND t.accountNumber = :accountNumber AND t.bankName = :bankName)
        ORDER BY t.timestamp DESC LIMIT :limit
        """,
    )
    fun observeByAccountLimited(
        accountNumber: String,
        bankName: String,
        limit: Int,
    ): Flow<List<TransactionEntity>>

    /** Most recent transaction for an account — the message behind the latest balance update. */
    @Query(
        """
        SELECT t.* FROM transactions t
        WHERE t.accountId = (
            SELECT a.id FROM accounts a
            WHERE a.accountNumber = :accountNumber AND a.bankName = :bankName LIMIT 1
        )
        OR (t.accountId IS NULL AND t.accountNumber = :accountNumber AND t.bankName = :bankName)
        ORDER BY t.timestamp DESC LIMIT 1
        """,
    )
    suspend fun latestForAccount(
        accountNumber: String,
        bankName: String,
    ): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE rawSmsId = :rawSmsId LIMIT 1")
    suspend fun findByRawSmsId(rawSmsId: Long): TransactionEntity?

    /**
     * Duplicate candidates by transaction reference (tier 1): rows whose
     * reference matches case-insensitively on the same account last-4, at
     * ANY time distance. Callers re-check the pair with
     * [app.clearsms.data.repository.TransactionDeduplication] — this only
     * narrows the scan.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE referenceNumber IS NOT NULL
          AND referenceNumber = :normalizedReference COLLATE NOCASE
          AND accountNumber = :accountNumber
        """,
    )
    suspend fun findByReference(
        normalizedReference: String,
        accountNumber: String,
    ): List<TransactionEntity>

    /**
     * Duplicate candidates by proximity (tier 2): same amount, type and
     * exact (last-4, bank) account inside a timestamp window. Callers
     * re-check each pair against the dedup guards.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE amount = :amount AND type = :type
          AND accountNumber = :accountNumber AND bankName = :bankName
          AND timestamp BETWEEN :fromTs AND :toTs
        """,
    )
    suspend fun findNearby(
        amount: Double,
        type: TransactionType,
        accountNumber: String,
        bankName: String,
        fromTs: Long,
        toTs: Long,
    ): List<TransactionEntity>

    @Update
    suspend fun update(transaction: TransactionEntity)

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
