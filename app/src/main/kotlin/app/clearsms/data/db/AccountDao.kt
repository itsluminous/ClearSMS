package app.clearsms.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY lastUpdated DESC")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE accountNumber = :accountNumber AND bankName = :bankName LIMIT 1")
    suspend fun find(
        accountNumber: String,
        bankName: String,
    ): AccountEntity?

    @Query("SELECT * FROM accounts ORDER BY id ASC")
    suspend fun getAll(): List<AccountEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: AccountEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(accounts: List<AccountEntity>)

    @Update
    suspend fun update(account: AccountEntity)

    @Query("UPDATE accounts SET creditLimit = :limit WHERE id = :accountId")
    suspend fun setCreditLimit(
        accountId: Long,
        limit: Double?,
    )

    @Query("DELETE FROM accounts")
    suspend fun deleteAll()
}
