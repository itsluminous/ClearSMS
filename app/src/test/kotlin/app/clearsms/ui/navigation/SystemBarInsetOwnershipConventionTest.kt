package app.clearsms.ui.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Source contract for edge-to-edge system-bar insets: ONE owner per inset.
 *
 * The bars own the system-bar insets - each screen's TopAppBar pads for the
 * status bar, and the shell's NavigationBar pads for the system navigation
 * bar. Applying the same inset a second time (the shell's default
 * contentWindowInsets, or an explicit inset modifier in screen content)
 * produced an inset-high dead strip under the status bar on every screen
 * and, with 3-button navigation, another above the bottom bar.
 *
 * 1. The shell scaffold contributes no content insets of its own and
 *    CONSUMES the bottom-bar padding, so the nested per-screen scaffolds
 *    cannot re-apply the navigation-bar inset that padding already covers.
 * 2. No screen content applies system-bar insets directly. The two allowed
 *    exceptions: the shared MessageComposerBar, whose expanded state
 *    deliberately reads the IME and navigation-bar insets live and pads for
 *    the status bar (pinned by ComposerExpansionConventionTest); and the
 *    shell itself, which owns the HORIZONTAL inset (rule 3).
 * 3. The bars own only VERTICAL insets, so in landscape the SIDE
 *    navigation-bar (and display-cutout) inset would have no owner - FABs
 *    and snackbars sat under the side bar. The shell's NavHost is the ONE
 *    horizontal owner: windowInsetsPadding of safeDrawing.only(Horizontal),
 *    which pads every routed screen AND consumes the inset so nothing
 *    nested can double it. No screen applies its own horizontal inset.
 */
class SystemBarInsetOwnershipConventionTest {
    private val srcRoot = File("src/main/kotlin/app/clearsms")

    private fun source(path: String): String = File(srcRoot, path).readText()

    @Test
    fun `the shell scaffold owns no content insets and consumes its bottom-bar padding`() {
        val shell = source("ui/navigation/ClearSmsApp.kt")
        // Without the zeroing, the shell pads the NavHost by the status bar
        // that every screen's TopAppBar pads for again.
        assertThat(shell).contains("contentWindowInsets = WindowInsets(0)")
        // Without the consumption, every nested Scaffold still sees the full
        // navigationBars inset and pads its content by it a second time.
        assertThat(shell).contains(".consumeWindowInsets(padding)")
    }

    @Test
    fun `the shell NavHost is the single owner of the horizontal inset`() {
        // In landscape with 3-button navigation the navigation bar sits on
        // the SIDE; the top/bottom bars own only vertical insets, so without
        // this owner the FABs (and anything else at a screen edge) are
        // clipped by the side bar. windowInsetsPadding also CONSUMES what it
        // pads, so nested scaffolds and the composer bar cannot re-apply it.
        val shell = source("ui/navigation/ClearSmsApp.kt")
        assertThat(shell)
            .contains(".windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))")
    }

    @Test
    fun `no screen applies its own horizontal inset - the shell is the one owner`() {
        // A per-screen horizontal inset on top of the shell's is a doubled
        // side gutter - the horizontal twin of the vertical dead strips
        // v0.18.2 removed.
        val allowed = setOf("ui/navigation/ClearSmsApp.kt")
        val forbidden =
            listOf(
                "WindowInsetsSides.Horizontal",
                "WindowInsetsSides.Left",
                "WindowInsetsSides.Right",
                "WindowInsetsSides.Start",
                "WindowInsetsSides.End",
                "displayCutoutPadding(",
                "WindowInsets.displayCutout",
            )
        val offenders =
            File(srcRoot, "ui")
                .walkTopDown()
                .filter { it.extension == "kt" }
                .filter { file -> forbidden.any { file.readText().contains(it) } }
                .map { it.relativeTo(srcRoot).path }
                .toList()
        assertThat(offenders).containsExactlyElementsIn(allowed)
    }

    @Test
    fun `screen content never applies a system-bar inset the bars already own`() {
        // Material bars pad for their own insets via their defaults; screen
        // content re-applying any of these duplicates an inset. The shared
        // composer bar and the shell (the sanctioned horizontal owner) are
        // the ONLY exceptions (see class doc).
        val allowed =
            setOf(
                "ui/components/MessageComposerBar.kt",
                "ui/navigation/ClearSmsApp.kt",
            )
        val forbidden =
            listOf(
                "statusBarsPadding(",
                "navigationBarsPadding(",
                "systemBarsPadding(",
                "safeDrawingPadding(",
                "safeContentPadding(",
                "WindowInsets.statusBars",
                "WindowInsets.navigationBars",
                "WindowInsets.systemBars",
                "WindowInsets.safeDrawing",
            )
        val offenders =
            File(srcRoot, "ui")
                .walkTopDown()
                .filter { it.extension == "kt" }
                .filter { file -> forbidden.any { file.readText().contains(it) } }
                .map { it.relativeTo(srcRoot).path }
                .toList()
        assertThat(offenders).containsExactlyElementsIn(allowed)
    }

    @Test
    fun `only the shell may declare scaffold contentWindowInsets`() {
        // A nested Scaffold given explicit contentWindowInsets is how a
        // second owner sneaks back in; the shell's zeroing is the only one.
        val offenders =
            File(srcRoot, "ui")
                .walkTopDown()
                .filter { it.extension == "kt" }
                .filter { it.readText().contains("contentWindowInsets") }
                .map { it.relativeTo(srcRoot).path }
                .toList()
        assertThat(offenders).containsExactly("ui/navigation/ClearSmsApp.kt")
    }
}
