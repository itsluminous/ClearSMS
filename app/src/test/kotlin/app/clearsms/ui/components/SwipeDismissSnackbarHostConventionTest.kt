package app.clearsms.ui.components

import androidx.compose.ui.geometry.Offset
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
        // A swipe must never trigger the snackbar's action (e.g. UNDO). The
        // assertion is scoped to the SWIPE handler: the action button in the
        // same file does call performAction, and quite rightly.
        val swipeHandler =
            source
                .substringAfter("LaunchedEffect(swipeState.currentValue)")
                .substringBefore("val contentColor")
        assertWithMessage("the swipe handler must dismiss")
            .that(swipeHandler)
            .contains("data.dismiss()")
        assertWithMessage("a swipe must not perform the snackbar action")
            .that(swipeHandler)
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
    fun `the snackbar background is translucent`() {
        assertWithMessage("snackbar opacity should let the message underneath show through")
            .that(SNACKBAR_CONTAINER_ALPHA)
            .isLessThan(1f)
        // A floor remains: below roughly half, the bar stops reading as a
        // surface at all and the text has nothing to sit on, shadow or not.
        assertWithMessage("the bar must still read as a surface")
            .that(SNACKBAR_CONTAINER_ALPHA)
            .isAtLeast(0.5f)
    }

    @Test
    fun `text carries a shadow whenever the background alone cannot guarantee contrast`() {
        // The two are a pair: this level of transparency is only readable
        // because the label and action are haloed. If the shadow ever goes,
        // the alpha has to go back up with it.
        val shadow = snackbarTextShadow()

        assertWithMessage("a translucent bar needs haloed text")
            .that(SNACKBAR_CONTAINER_ALPHA < 0.85f)
            .isTrue()
        assertWithMessage("the halo must actually be drawn")
            .that(shadow.blurRadius)
            .isGreaterThan(0f)
        assertWithMessage("the halo must be dark enough to lift light glyphs off a pale background")
            .that(shadow.color.alpha)
            .isAtLeast(0.5f)
        assertWithMessage("a halo reads better than a drop shadow at label sizes")
            .that(shadow.offset)
            .isEqualTo(Offset.Zero)
    }

    @Test
    fun `taking over the snackbar content keeps the action and dismiss affordances`() {
        val source = File(srcRoot, componentFile).readText()

        // Rendering our own content means the defaults are gone, so both must
        // be rebuilt - an UNDO snackbar with no working action is a data-loss
        // bug, not a cosmetic one.
        assertWithMessage("the action must still invoke performAction")
            .that(source)
            .contains("data.performAction()")
        assertWithMessage("a snackbar asking for a dismiss affordance must still get one")
            .that(source)
            .contains("data.visuals.withDismissAction")
        assertWithMessage("the message text must come from the snackbar data")
            .that(source)
            .contains("data.visuals.message")
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
