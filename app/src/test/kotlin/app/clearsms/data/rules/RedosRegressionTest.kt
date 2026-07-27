package app.clearsms.data.rules

import app.clearsms.domain.categorizer.ContactLookup
import app.clearsms.domain.categorizer.MessageCategorizer
import app.clearsms.domain.categorizer.SenderIdLookup
import app.clearsms.domain.model.Category
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File

/**
 * Regression tests for catastrophic regex backtracking (ReDoS) on
 * attacker-controlled SMS bodies, and for the RuleEngine evaluation budget
 * that guards against pathological user-imported rules.
 */
class RedosRegressionTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun bundledRules(): List<RuleDefinition> {
        val file =
            listOf(
                File("src/main/assets/default_rules.json"),
                File("app/src/main/assets/default_rules.json"),
            ).firstOrNull { it.exists() }
        checkNotNull(file) { "default_rules.json asset not found" }
        return json.decodeFromString(RuleDocument.serializer(), file.readText()).rules
    }

    /** Long body that partially matches transactional patterns but never fully. */
    private fun hostileBody(length: Int): String {
        val unit = "debited Rs.1,2,3,4 a/c X"
        return buildString(length + unit.length) {
            while (this.length < length) append(unit)
        }
    }

    @Test
    fun `no bundled pattern starts or ends with a wildcard wrapper`() {
        for (rule in bundledRules()) {
            val pattern = rule.match.bodyPattern ?: continue
            val flagless = pattern.removePrefix("(?i)")
            assertThat(flagless.startsWith("[\\s\\S]*")).isFalse()
            assertThat(flagless.startsWith(".*")).isFalse()
            assertThat(pattern.endsWith("[\\s\\S]*")).isFalse()
        }
    }

    @Test
    fun `hostile 10k-char body is categorized well under a second`() {
        val categorizer =
            MessageCategorizer(
                ruleEngine = RuleEngine(),
                senderIdLookup = SenderIdLookup { null },
                contactLookup = ContactLookup { false },
            )
        val rules = bundledRules()
        val body = hostileBody(10_000)

        val startNs = System.nanoTime()
        categorizer.categorize(
            sender = "AX-SPAMMY",
            body = body,
            userRules = emptyList(),
            builtinRules = rules,
        )
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000

        // Actual time is a few milliseconds; the generous ceiling keeps slow
        // CI machines from flaking while still catching a reintroduced
        // catastrophic pattern (which would take minutes).
        assertThat(elapsedMs).isLessThan(1_000)
    }

    @Test
    fun `evaluation budget skips remaining rules and reports the offender`() {
        val logged = mutableListOf<String>()
        // Fake clock: every call advances 300ms, so the budget (250ms) is
        // exceeded right after the first rule evaluates.
        var fakeNanos = 0L
        val engine =
            RuleEngine(
                log = { logged += it },
                nanoTime = {
                    fakeNanos += 300_000_000L
                    fakeNanos
                },
            )
        val slowRule =
            RuleDefinition(
                id = "pathological-1",
                priority = 100,
                match = RuleMatch(bodyPattern = "never-matches"),
                action = RuleAction(category = "promotional"),
            )
        val catchAll =
            RuleDefinition(
                id = "catch-all",
                priority = 1,
                match = RuleMatch(bodyPattern = "(?i)hello"),
                action = RuleAction(category = "personal"),
            )

        val result = engine.evaluate(listOf(catchAll, slowRule), "SENDER", "hello world")

        assertThat(result).isNull()
        assertThat(logged.single()).contains("pathological-1")
        assertThat(logged.single()).contains("budget")
    }

    @Test
    fun `budget does not fire for a normal rule set`() {
        val engine = RuleEngine()
        val catchAll =
            RuleDefinition(
                id = "catch-all",
                priority = 1,
                match = RuleMatch(bodyPattern = "(?i)hello"),
                action = RuleAction(category = "personal"),
            )
        val result = engine.evaluate(listOf(catchAll), "SENDER", "hello world")
        assertThat(result).isNotNull()
        assertThat(result!!.category).isEqualTo(Category.PERSONAL)
    }
}
