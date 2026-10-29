package app.clearsms.data.rules

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File

/**
 * A whitespace-padded body is the one hostile shape `RedosRegressionTest`
 * does not cover: its hostile body is a repeated transactional phrase, which
 * exercises alternation but not the ambiguity of a lazy run sandwiched
 * between two `\s+` gaps.
 *
 * `\s+([^\n]{1,40}?)\s+` can be split many ways across a run of spaces, and
 * the engine backtracks through them. Twelve bundled rules carry that shape.
 * This measures them under `java.util.regex` - the engine that actually runs
 * on a phone - so the cost is a number rather than an assumption, and so a
 * future pattern that makes it catastrophic fails the build instead of
 * silently eating RuleEngine's evaluation budget.
 */
class WhitespacePaddedBodyTest {
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

    /**
     * Bodies that open like a real transaction, then pad with spaces so a
     * lazy run between two whitespace gaps has many candidate splits.
     */
    private fun paddedBodies(): List<String> =
        listOf(
            "paid Rs 1 to " + " ".repeat(400) + "with Paytm Ref " + "X".repeat(300),
            "PKR 1 charged at " + " ".repeat(400) + "as internet purchase, from A/C " + "X".repeat(300),
            "Rs 1 debited from " + " ".repeat(400) + "on 01-Jan " + "X".repeat(300),
            "spent Rs 1 at " + " ".repeat(400) + "on your card " + "X".repeat(300),
        )

    @Test
    fun `no single bundled pattern stalls on a whitespace padded body`() {
        val slow = mutableListOf<Pair<String, Long>>()
        for (rule in bundledRules()) {
            val pattern = rule.match.bodyPattern ?: continue
            val regex = Regex(pattern)
            for (body in paddedBodies()) {
                val startNs = System.nanoTime()
                regex.containsMatchIn(body)
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
                // RuleEngine's whole-message budget is 250ms, so any SINGLE
                // pattern approaching it would starve the rules after it.
                if (elapsedMs > 100) slow += rule.id to elapsedMs
            }
        }
        assertThat(slow).isEmpty()
    }

    @Test
    fun `the whole bundle evaluates a whitespace padded body within the engine budget`() {
        val rules = bundledRules()
        for (body in paddedBodies()) {
            val startNs = System.nanoTime()
            for (rule in rules) {
                rule.match.bodyPattern?.let { Regex(it).containsMatchIn(body) }
            }
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
            // Generous ceiling: this compiles every pattern as it goes, which
            // the app does once at load. It catches a catastrophic pattern
            // (minutes) without flaking on a slow CI machine.
            assertThat(elapsedMs).isLessThan(5_000)
        }
    }
}
