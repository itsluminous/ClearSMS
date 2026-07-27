package app.clearsms.data.db

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
)
