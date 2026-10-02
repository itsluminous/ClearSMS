package app.clearsms.ui.conversation

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * How the sender name fits the conversation top bar: it may wrap to
 * [MaxLines] lines and shrink from the bar's title size down to - never
 * below - [MinFontSize]; whatever still does not fit ellipsises.
 *
 * The bar is a fixed-height Material3 `TopAppBar` ([MaxHeight]); the name
 * must fit INSIDE it, so the avatar and the Call/overflow buttons never
 * move and the bar never grows. Two lines are at most 2 x [LineHeight]
 * tall: at font scale 1.0 and the 22sp title size that is 2 x 26.4 =
 * 52.8dp of the 64dp bar, 11.2dp to spare; at the 16sp floor, 38.4dp. At
 * larger font scales the auto-sizer first shrinks the name (never past the
 * floor) until two lines fit, and only when even the floor's two lines
 * would not fit does Compose's height-aware ellipsis keep the name to the
 * one line that does (see `ConversationTitleFitTest`).
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
     * Line height as a MULTIPLE of the font, not a fixed sp value.
     *
     * `TextAutoSize.StepBased` shrinks `fontSize` only; it leaves
     * `lineHeight` exactly as the style declares it. titleLarge declares
     * 22sp on a 28sp line, so a name shrunk to the 16sp floor still sat on
     * a 28sp line - a 1.75 ratio where titleLarge intends 28/22 = 1.27 -
     * and the two halves of one name read as two different names. An em
     * line height is resolved against whatever font size the auto-sizer
     * chose, so the ratio holds at every step.
     *
     * 1.2 rather than titleLarge's 1.27: these two lines are ONE name
     * broken for width, not two paragraphs, so they sit a little closer
     * than running text would - the same leading Material3 gives its
     * single-unit display styles (displaySmall is 36sp on 44sp = 1.22).
     * Roboto's ascent + descent is ~1.17em, so 1.2em still clears the
     * glyph box: descenders never touch the next line's capitals.
     */
    val LineHeight: TextUnit = 1.2.em

    /**
     * Trim the half-leading ABOVE the first line and BELOW the last one,
     * and split what remains between lines in the font's own
     * ascent:descent proportion. Trimming matters because the extra
     * height a line height adds is spread around every line, including
     * the outside of the first and last: untrimmed, the first line of a
     * wrapped name would float that much lower in the bar, and the name's
     * single-line height would change too. With trim, a one-line name is
     * exactly its glyph box tall, whatever the line height - which is why
     * a short name renders exactly as before.
     *
     * These are Compose's defaults for a style without `includeFontPadding`
     * (Material3 and this app's typography both leave it off), pinned here
     * so a future theme change cannot quietly reintroduce the padding.
     */
    val LineTrim: LineHeightStyle =
        LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Proportional,
            trim = LineHeightStyle.Trim.Both,
        )

    /**
     * The bar's title style with ONLY its line metrics replaced by
     * [LineHeight] and [LineTrim]: font, weight, size, colour and
     * everything else stay the bar's, so a short name is the Text it was
     * before.
     */
    fun style(barTitleStyle: TextStyle): TextStyle = barTitleStyle.copy(lineHeight = LineHeight, lineHeightStyle = LineTrim)

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
