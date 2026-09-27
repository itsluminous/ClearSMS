package app.clearsms.ui.rules

import app.clearsms.data.db.RuleEntity
import app.clearsms.data.rules.RuleAction
import app.clearsms.data.rules.RuleDefinition
import app.clearsms.data.rules.RuleMatch
import app.clearsms.data.rules.RuleSources
import kotlinx.serialization.json.Json

/**
 * Pure builder for the rules page state - the one place the list is
 * assembled, kept free of coroutines so the invariants that keep the page
 * from crashing are unit-testable.
 *
 * Invariants (issue #43 - the page crashed on open for a user who had
 * created a rule, until they wiped app data):
 *
 * - **One item per rule id.** The `LazyColumn` keys rows on `user_<id>` /
 *   `builtin_<id>`, and Compose throws `IllegalArgumentException: Key was
 *   already used` for a duplicate - the page cannot open, so the user can
 *   never reach the delete button. A rule can legitimately be present twice
 *   in the inputs: the legacy disable path parked a rule in preferences AND
 *   deleted its row in two separate writes, so a process death between them
 *   (or a bundled reseed re-inserting a parked builtin) leaves it both in
 *   the table and in the parked set. The table row wins - it is what the
 *   engine evaluates - and every duplicate is dropped, whichever store it
 *   came from.
 * - **A malformed row still renders.** Nothing here decodes a rule's
 *   category, pattern or extracts in a way that can throw: an undecodable
 *   JSON blob only sets [RuleItem.malformed], so the row appears in a
 *   degraded form with its delete button, instead of taking the page down.
 * - **Unreadable parked entries are counted, not thrown.** A parked entry
 *   that no longer decodes is skipped and reported via
 *   [RulesUiState.unreadable] so its existence is visible.
 */
object RulesUiStateBuilder {
    fun build(
        rules: List<RuleEntity>,
        parked: Set<String>,
        json: Json,
    ): RulesUiState {
        val items = LinkedHashMap<String, RuleItem>()
        for (rule in rules) {
            // Primary keys make table ids unique, but never trust a single
            // source: the map is what guarantees the LazyColumn key set.
            items.putIfAbsent(
                rule.id,
                RuleItem(
                    id = rule.id,
                    name = rule.name,
                    isUserDefined = rule.isUserDefined,
                    enabled = rule.enabled,
                    malformed = !isDecodable(rule, json),
                ),
            )
        }
        var unreadable = 0
        for (entry in parked) {
            val item = parkedToItem(entry, json)
            if (item == null) {
                unreadable++
                continue
            }
            // A table row with the same id shadows the parked copy: the
            // engine evaluates the row, so the row's state is the truth.
            items.putIfAbsent(item.id, item)
        }
        val all = items.values
        return RulesUiState(
            builtinRules = all.filter { !it.isUserDefined }.sortedBy { it.name },
            userRules = all.filter { it.isUserDefined }.sortedBy { it.name },
            loaded = true,
            unreadable = unreadable,
        )
    }

    /** Whether both JSON halves of [rule] decode; false marks the row degraded. */
    fun isDecodable(
        rule: RuleEntity,
        json: Json,
    ): Boolean =
        try {
            json.decodeFromString(RuleMatch.serializer(), rule.matchJson)
            json.decodeFromString(RuleAction.serializer(), rule.actionJson)
            true
        } catch (_: Exception) {
            false
        }

    /** Decodes a legacy `source|definitionJson` parked entry; null when unreadable. */
    fun parkedDefinition(
        entry: String,
        json: Json,
    ): Pair<String, RuleDefinition>? {
        val source = entry.substringBefore('|')
        if (source.isEmpty() || !entry.contains('|')) return null
        val definition =
            try {
                json.decodeFromString(RuleDefinition.serializer(), entry.substringAfter('|'))
            } catch (_: Exception) {
                return null
            }
        return source to definition
    }

    private fun parkedToItem(
        entry: String,
        json: Json,
    ): RuleItem? {
        val (source, definition) = parkedDefinition(entry, json) ?: return null
        return RuleItem(
            id = definition.id,
            name = definition.name ?: definition.id,
            isUserDefined = source == RuleSources.USER,
            enabled = false,
            parkedEntry = entry,
        )
    }
}
