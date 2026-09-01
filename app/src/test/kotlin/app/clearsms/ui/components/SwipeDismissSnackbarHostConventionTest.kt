package app.clearsms.ui.components

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Source-level convention: every screen's `snackbarHost` is
 * [SwipeDismissSnackbarHost], never a bare `SnackbarHost`.
 *
 * Snackbars cover the bottom of the screen - precisely where a message you
 * just sent appears - so a snackbar that can only time out hides your own
 * message for its whole duration. Routing every screen through one host keeps
 * the gesture uniform, and a new screen that reaches for the Material default
 * would silently lose it: hence this test rather than a code comment.
 */
class SwipeDismissSnackbarHostConventionTest {
    private val srcRoot = File("src/main/kotlin/app/clearsms")

    private val componentFile = "ui/components/SwipeDismissSnackbarHost.kt"

    private fun kotlinSources(): List<File> =
        srcRoot
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    @Test
    fun `no screen wires a bare SnackbarHost into its scaffold`() {
        val offenders =
            kotlinSources()
                .filterNot { it.path.endsWith(componentFile) }
                .filter { file ->
                    file
                        .readText()
                        .lineSequence()
                        .filterNot { it.trimStart().startsWith("import ") }
                        .any { line -> Regex("(?<![A-Za-z])SnackbarHost\\(").containsMatchIn(line) }
                }.map { it.name }

        assertWithMessage(
            "these files use a bare SnackbarHost - use SwipeDismissSnackbarHost so the " +
                "snackbar can be swiped away instead of hiding the message underneath it",
        ).that(offenders)
            .isEmpty()
    }

    @Test
    fun `the shared host dismisses on a swipe in either direction`() {
        val source = File(srcRoot, componentFile).readText()

        // A non-Settled swipe value means the user pushed it away: dismiss.
        assertWithMessage("swiping must dismiss the snackbar")
            .that(source)
            .contains("data.dismiss()")
        assertWithMessage("both swipe directions must dismiss (no direction filter)")
            .that(source)
            .contains("!= SwipeToDismissBoxValue.Settled")
        // A swipe must never trigger the snackbar's action (e.g. UNDO).
        assertWithMessage("a swipe must not perform the snackbar action")
            .that(source)
            .doesNotContain("performAction")
    }

    @Test
    fun `swipe state is keyed per snackbar so the next one is not born dismissed`() {
        val source = File(srcRoot, componentFile).readText()

        assertWithMessage(
            "the swipe state must be keyed on the snackbar data; a state shared across " +
                "snackbars stays dismissed and swallows every later snackbar",
        ).that(source)
            .contains("key(data)")
    }

    @Test
    fun `every screen that shows snackbars is covered`() {
        // Guards against the convention passing trivially because a screen
        // dropped its host entirely.
        val hostUsers =
            kotlinSources()
                .filter { it.readText().contains("SwipeDismissSnackbarHost(snackbarHostState)") }
                .map { it.name }
                .sorted()

        assertWithMessage("expected every snackbar-showing screen to use the shared host")
            .that(hostUsers)
            .containsExactly(
                "AccountDetailScreen.kt",
                "AlertsScreen.kt",
                "ArchivedScreen.kt",
                "BinScreen.kt",
                "ComposeMessageScreen.kt",
                "ConversationScreen.kt",
                "FinanceScreen.kt",
                "InboxScreen.kt",
                "RulesScreen.kt",
                "SettingsScreen.kt",
            )
    }

    @Test
    fun `the snackbar background is translucent but still legible`() {
        // The message just sent sits behind the bar, so a little of it should
        // show through - without the text losing contrast against a busy
        // bubble, which is what the lower bound guards.
        assertWithMessage("snackbar opacity should let some background through")
            .that(SNACKBAR_CONTAINER_ALPHA)
            .isLessThan(1f)
        assertWithMessage("below ~0.85 the text starts fighting the bubbles behind it")
            .that(SNACKBAR_CONTAINER_ALPHA)
            .isAtLeast(0.85f)
    }

    @Test
    fun `the host sets its own container and content colours together`() {
        val source = File(srcRoot, componentFile).readText()

        // A translucent container with the default content colour would be a
        // contrast bug waiting to happen, so both are set explicitly.
        assertWithMessage("container colour must apply the alpha")
            .that(source)
            .contains("copy(alpha = SNACKBAR_CONTAINER_ALPHA)")
        assertWithMessage("content colour must be set alongside the container")
            .that(source)
            .contains("contentColor =")
        assertWithMessage("the action label needs a colour that survives the transparency")
            .that(source)
            .contains("actionColor =")
    }
}
