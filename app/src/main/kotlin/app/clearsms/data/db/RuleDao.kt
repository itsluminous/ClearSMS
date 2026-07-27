package app.clearsms.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY priority DESC")
    fun observeAll(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules WHERE source = :source ORDER BY priority DESC")
    suspend fun getBySource(source: String): List<RuleEntity>

    @Query("SELECT * FROM rules ORDER BY id ASC")
    suspend fun getAll(): List<RuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: RuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<RuleEntity>)

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM rules WHERE source = :source")
    suspend fun deleteBySource(source: String)

    @Query("DELETE FROM rules")
    suspend fun deleteAll()
}
