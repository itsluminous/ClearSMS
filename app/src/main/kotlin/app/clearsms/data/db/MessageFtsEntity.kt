package app.clearsms.data.db

import androidx.room.Entity
import androidx.room.Fts4

/**
 * FTS4 index over message sender and body, external-content backed by
 * [MessageEntity] (Room keeps it in sync with triggers on `messages`).
 *
 * Search runs `MATCH 'token*'` against this table instead of a
 * `LIKE '%q%'` full-table scan: the LIKE form can never use an index, so on
 * a ~14.5k-row inbox every keystroke re-read the whole table. The trade-off
 * is FTS matches token *prefixes* ("sal" finds "Salary", but "alary" does
 * not) - the right behaviour for search-as-you-type.
 */
@Fts4(contentEntity = MessageEntity::class)
@Entity(tableName = "messages_fts")
data class MessageFtsEntity(
    val sender: String,
    val body: String,
)
