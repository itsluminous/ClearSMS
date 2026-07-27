package app.clearsms.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A categorization rule (bundled or user-defined) stored as its JSON parts. */
@Entity(
    tableName = "rules",
    indices = [Index("source"), Index("priority")],
)
data class RuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val priority: Int,
    /** JSON of the rule's `match` block (schema per the bundled rules document). */
    val matchJson: String,
    /** JSON of the rule's `action` block. */
    val actionJson: String,
    val isUserDefined: Boolean,
    /** Origin of the rule: "builtin", "user" or "community". */
    val source: String,
    val createdAt: Long,
    /**
     * Whether the engine evaluates this rule. Disabling flips this flag in
     * place instead of deleting the row, so a rule's identity and [source]
     * survive an enable/disable round trip.
     */
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
) {
    companion object {
        /**
         * Namespace prefix forced onto rule ids that enter the database from
         * user-supplied documents (rule imports, backup restores). Bundled
         * rule ids are public in the repository, so without the prefix a
         * crafted document could choose a builtin's id and silently overwrite
         * it via the REPLACE insert strategy.
         */
        const val USER_ID_PREFIX = "user:"

        /** Returns [id] namespaced into the user-rule id space. */
        fun namespacedUserId(id: String): String = if (id.startsWith(USER_ID_PREFIX)) id else USER_ID_PREFIX + id
    }
}
