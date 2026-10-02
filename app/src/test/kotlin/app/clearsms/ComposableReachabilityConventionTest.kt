package app.clearsms

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Source-level invariant: every non-private top-level `@Composable` declared
 * in main is CALLED from main (previews excepted).
 *
 * Why this matters for the release build: R8 deletes an uncalled composable,
 * and the R8-integrated resource shrinker (AGP 9+) then correctly drops any
 * `R.string` / `R.drawable` that only that composable referenced. In source
 * the resource still looks "live", so a dead composable makes the shrinker
 * report read like a lost translation or a crash-in-waiting when nothing is
 * wrong - exactly what happened with `BalanceEyeButton` and its
 * `balance_reveal` / `balance_conceal` labels during the AGP 9 migration.
 * Keeping main free of uncalled composables keeps the shrinker's output
 * explainable: an app resource it removes is then either declared-but-never
 * referenced (lint `UnusedResources`) or a genuine shrinker bug.
 *
 * Pure text contract on purpose: this module has no Compose UI test harness.
 */
class ComposableReachabilityConventionTest {
    private val mainRoot = File("src/main/kotlin")

    private fun mainSources(): List<File> =
        mainRoot
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
            .also { assertWithMessage("expected Kotlin sources under $mainRoot").that(it).isNotEmpty() }

    /** `fun Name(` / `internal fun Name(` at column 0, i.e. a top-level, non-private declaration. */
    private val topLevelFun = Regex("""^(?:internal\s+)?fun\s+([A-Z][A-Za-z0-9_]*)\s*\(""")

    private fun declaredComposables(file: File): List<String> {
        val lines = file.readLines()
        val names = mutableListOf<String>()
        for ((index, line) in lines.withIndex()) {
            val match = topLevelFun.find(line) ?: continue
            // Walk back over the annotation block directly above the declaration.
            var composable = false
            var preview = false
            var i = index - 1
            while (i >= 0 && lines[i].trimStart().startsWith("@")) {
                val annotation = lines[i].trim()
                if (annotation.startsWith("@Composable")) composable = true
                if (annotation.startsWith("@Preview")) preview = true
                i--
            }
            if (composable && !preview) names += match.groupValues[1]
        }
        return names
    }

    @Test
    fun `every public top-level composable is invoked somewhere in main`() {
        val sources = mainSources()
        val texts = sources.associateWith { it.readText() }
        val declared = sources.flatMap { file -> declaredComposables(file).map { it to file } }
        assertWithMessage("expected to find top-level composables to check").that(declared).isNotEmpty()

        val uncalled =
            declared.filter { (name, declaringFile) ->
                val call = Regex("""(?<![A-Za-z0-9_.])$name\s*\(""")
                texts.none { (file, text) ->
                    // Any call site counts, including one inside the declaring file,
                    // but the declaration line itself must not.
                    val hits = call.findAll(text).count()
                    val declarationsHere = if (file == declaringFile) 1 else 0
                    hits > declarationsHere
                }
            }

        assertWithMessage(
            "Uncalled top-level composables (R8 removes them, and the resource shrinker then drops " +
                "whatever they alone referenced - delete them, or call them): " +
                uncalled.map { (name, file) -> "$name in ${file.relativeTo(mainRoot)}" },
        ).that(uncalled).isEmpty()
    }
}
