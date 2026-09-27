package app.clearsms.ui.rules

import app.clearsms.data.db.RuleEntity
import app.clearsms.data.rules.RuleAction
import app.clearsms.data.rules.RuleDefinition
import app.clearsms.data.rules.RuleMatch
import app.clearsms.data.rules.RuleSources
import app.clearsms.data.rules.toEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * The rules page must open for ANY persisted state (issue #43: a user who
 * had created a rule saw "Manage rules" crash on every open until they wiped
 * app data - and being unable to open the page meant being unable to delete
 * the bad rule). These tests pin the two properties that guarantee it:
 * the list keys the `LazyColumn` uses are unique, and no row content can
 * throw while the list is built.
 */
class RulesUiStateBuilderTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun row(
        id: String,
        name: String = "Rule $id",
        matchJson: String = """{"body_pattern":"debited"}""",
        actionJson: String = """{"category":"important"}""",
        source: String = RuleSources.USER,
        enabled: Boolean = true,
    ) = RuleEntity(
        id = id,
        name = name,
        priority = 100,
        matchJson = matchJson,
        actionJson = actionJson,
        isUserDefined = source == RuleSources.USER,
        source = source,
        createdAt = 0L,
        enabled = enabled,
    )

    private fun parked(
        id: String,
        source: String = RuleSources.USER,
    ): String {
        val definition = RuleDefinition(id = id, name = "Parked $id", action = RuleAction(category = "important"))
        return "$source|" + json.encodeToString(RuleDefinition.serializer(), definition)
    }

    /** The exact keys `RulesScreen` hands to `LazyColumn.items`. */
    private fun lazyKeys(state: RulesUiState): List<String> =
        state.userRules.map { "user_${it.id}" } + state.builtinRules.map { "builtin_${it.id}" }

    @Test
    fun `hand-made rows with odd content all render and none is dropped`() {
        val rows =
            listOf(
                row("unknown-category", actionJson = """{"category":"not_a_category"}"""),
                row("unknown-sub", actionJson = """{"category":"important","sub_category":"no_such_sub"}"""),
                row("bad-regex", matchJson = """{"body_pattern":"(unclosed[","sender_pattern":"*"}"""),
                row("empty-pattern", matchJson = """{"body_pattern":""}"""),
                row("blank-name", name = ""),
                row("no-extracts", actionJson = """{"category":"important","extract":{}}"""),
                row("no-category", actionJson = """{}"""),
                row("garbage-json", matchJson = "not json at all", actionJson = "{"),
                row("old-spam-value", actionJson = """{"category":"spam"}""", source = RuleSources.BUILTIN),
            )

        val state = RulesUiStateBuilder.build(rows, emptySet(), json)

        assertThat(state.userRules.size + state.builtinRules.size).isEqualTo(rows.size)
        assertThat(lazyKeys(state)).containsNoDuplicates()
        // Only truly undecodable JSON is degraded; unknown category names,
        // bad regexes and blanks are the engine's business, not the list's.
        assertThat(state.userRules.filter { it.malformed }.map { it.id })
            .containsExactly("no-category", "garbage-json")
        val garbage = state.userRules.single { it.id == "garbage-json" }
        assertThat(garbage.canOpen).isFalse()
        assertThat(garbage.enabled).isTrue()
    }

    @Test
    fun `a rule both in the table and parked appears once, table row wins`() {
        // The persisted shape a process death mid-toggle (or a reseed of a
        // parked builtin) leaves behind: same id in both stores.
        val state =
            RulesUiStateBuilder.build(
                rules = listOf(row("dup-user"), row("dup-builtin", source = RuleSources.BUILTIN)),
                parked = setOf(parked("dup-user"), parked("dup-builtin", RuleSources.BUILTIN)),
                json = json,
            )

        assertThat(lazyKeys(state)).containsNoDuplicates()
        assertThat(state.userRules.single { it.id == "dup-user" }.enabled).isTrue()
        assertThat(state.userRules.single { it.id == "dup-user" }.parkedEntry).isNull()
        assertThat(state.builtinRules.single { it.id == "dup-builtin" }.enabled).isTrue()
    }

    @Test
    fun `two parked entries for one id collapse to one row`() {
        val a = parked("twice")
        val b = a.replace("Parked twice", "Parked twice (older copy)")
        assertThat(a).isNotEqualTo(b)

        val state = RulesUiStateBuilder.build(emptyList(), setOf(a, b), json)

        assertThat(lazyKeys(state)).containsNoDuplicates()
        assertThat(state.userRules).hasSize(1)
        assertThat(state.userRules.single().enabled).isFalse()
    }

    @Test
    fun `an unreadable parked entry is skipped and counted, never thrown`() {
        val state =
            RulesUiStateBuilder.build(
                rules = listOf(row("ok")),
                parked = setOf("user|{not json", "no-separator-at-all", parked("fine")),
                json = json,
            )

        assertThat(state.unreadable).isEqualTo(2)
        assertThat(state.userRules.map { it.id }).containsExactly("ok", "fine")
        assertThat(state.loaded).isTrue()
    }

    @Test
    fun `a user row shadows a bundled row of the same id across sections`() {
        // A parked "builtin" entry and a USER table row can share an id (the
        // legacy re-enable path inserted re-enabled builtins as user rules).
        // Section keys differ, but the rule must still be listed once.
        val definition = RuleDefinition(id = "shared", name = "Shared", action = RuleAction(category = "otp"))
        val state =
            RulesUiStateBuilder.build(
                rules = listOf(definition.toEntity(json, RuleSources.USER)),
                parked = setOf(parked("shared", RuleSources.BUILTIN)),
                json = json,
            )

        assertThat(state.userRules.map { it.id }).containsExactly("shared")
        assertThat(state.builtinRules).isEmpty()
    }

    @Test
    fun `disabled table rows render disabled without a parked entry`() {
        val state = RulesUiStateBuilder.build(listOf(row("off", enabled = false)), emptySet(), json)

        val item = state.userRules.single()
        assertThat(item.enabled).isFalse()
        assertThat(item.parkedEntry).isNull()
        assertThat(item.canOpen).isFalse()
    }

    @Test
    fun `isDecodable accepts every shape the wizard or engine can produce`() {
        assertThat(RulesUiStateBuilder.isDecodable(row("min", matchJson = "{}"), json)).isTrue()
        assertThat(RulesUiStateBuilder.isDecodable(row("bad", actionJson = "[]"), json)).isFalse()
        assertThat(
            RulesUiStateBuilder.isDecodable(
                RuleDefinition(id = "x", match = RuleMatch(), action = RuleAction(category = ""))
                    .toEntity(json, RuleSources.USER),
                json,
            ),
        ).isTrue()
    }
}
