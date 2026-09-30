package app.clearsms.ui.conversation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * How the sender name fits the conversation top bar: it may wrap to
 * [MaxLines] lines and shrink from the bar's title size down to - never
 * below - [MinFontSize]; whatever still does not fit ellipsises.
 *
 * The bar is a fixed-height Material3 `TopAppBar` ([MaxHeight]); the name
 * must fit INSIDE it, so the avatar and the Call/overflow buttons never
 * move and the bar never grows. At font scale 1.0 the title style
 * (titleLarge, 22sp on a 28sp line) fits two lines in 56dp with 4dp to
 * spare; at larger font scales two lines no longer fit, and Compose's
 * height-aware ellipsis keeps the name to the lines that do (see
 * `ConversationTitleFitTest`).
 *
 * The floor is in sp ON PURPOSE: it scales with the user's font setting, so
 * auto-shrinking can never undo a large-font accessibility choice - at
 * scale 2.0 the smallest the name can render is 2 x 16sp, and a name that
 * still does not fit ellipsises instead of shrinking further.
 */
object ConversationTitleFit {
    /** Wrap to at most this many lines before ellipsising. */
    val MaxLines: Int = 2

    /**
     * Hard floor for auto-sizing: Material3's titleMedium / bodyLarge size,
     * the smallest the app's text runs. Below this the name would read as
     * metadata rather than a title.
     */
    val MinFontSize: TextUnit = 16.sp

    /** Auto-size granularity: whole sp steps between the floor and the title size. */
    val StepSize: TextUnit = 1.sp

    /**
     * The `TopAppBar` container height (Material3 `TopAppBarSmallTokens
     * .ContainerHeight`). The name's layout is capped here so, with
     * ellipsis on, lines that would not fit the bar are dropped rather
     * than drawn over its edges.
     */
    val MaxHeight: Dp = 64.dp

    /** The bar's title size when the provided style carries none (Material3 titleLarge). */
    val FallbackMaxFontSize: TextUnit = 22.sp

    /**
     * The largest size auto-sizing may use: the bar's own title style, so a
     * short name is exactly the Text it was before. Falls back to titleLarge
     * when the style is unspecified or not in sp, and never sits below the
     * floor - a degenerate range would let the floor be "shrunk" past.
     */
    fun maxFontSize(styleFontSize: TextUnit): TextUnit {
        val candidate = if (styleFontSize.isSp) styleFontSize else FallbackMaxFontSize
        return if (candidate.value >= MinFontSize.value) candidate else MinFontSize
    }
}
