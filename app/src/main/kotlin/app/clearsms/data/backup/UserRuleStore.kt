package app.clearsms.data.backup

import androidx.room.withTransaction
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.RuleEntity
import app.clearsms.data.rules.RuleSources

/**
 * The slice of the rules table the settings backup reads and writes: the
 * user's OWN rules. Bundled rules are invisible through this interface, so
 * [SettingsBackupManager] cannot export or overwrite them even by mistake.
 *
 * Kept as an interface so the backup logic is unit-testable with an
 * in-memory fake instead of a Room database.
 */
interface UserRuleStore {
    /** Every rule whose [RuleEntity.source] is [RuleSources.USER]. */
    suspend fun userRules(): List<RuleEntity>

    /**
     * Inserts or replaces [rules] by id in ONE transaction. Callers guarantee
     * every row is user-sourced and that no id belongs to a bundled rule.
     */
    suspend fun upsertUserRules(rules: List<RuleEntity>)
}

/** Room-backed [UserRuleStore]. */
class RoomUserRuleStore(
    private val database: ClearSmsDatabase,
) : UserRuleStore {
    override suspend fun userRules(): List<RuleEntity> = database.ruleDao().getBySource(RuleSources.USER)

    override suspend fun upsertUserRules(rules: List<RuleEntity>) {
        database.withTransaction { database.ruleDao().insertAll(rules) }
    }
}
