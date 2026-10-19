package app.clearsms.ui.conversation

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
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
 * one line that does (see `ConversationTitleFitTest`, and [linesFit] for
 * the arithmetic at a given font scale).
 *
 * The floor is in sp ON PURPOSE: it scales with the user's font setting, so
 * auto-shrinking can never undo a large-font accessibility choice - at
 * scale 2.0 the smallest the name can render is 16sp as that setting
 * renders it, and a name that still does not fit ellipsises instead of
 * shrinking further.
 *
 * The sizing itself is [autoSize], this file's own `TextAutoSize`, NOT
 * `TextAutoSize.StepBased`. StepBased searches in PIXELS: it converts the
 * floor, the ceiling and the 1sp step to px and walks a px grid anchored
 * at the floor. Android (and Compose, on every API level) scales sp to dp
 * NON-LINEARLY from font scale 1.03 up - at 1.5, 16sp is 23dp and 22sp is
 * 27dp (not 24 and 33), while a 1sp step converts at the small-size slope
 * to 1.5dp - so the grid from the floor runs 23, 24.5, 26dp and the next
 * point, 27.5dp, overshoots the 27dp ceiling. StepBased therefore never
 * lays the title out at 22sp at all at that scale and returns the 26dp
 * point, 20sp, for EVERY name, however short (the "shrunken short name at
 * large font" defect). [autoSize] walks the sp steps themselves, from the
 * ceiling down, so a name that fits at the bar's title size renders at it
 * at every font scale, and a long name takes the largest sp step that fits.
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

    /**
     * The title's auto-sizer: the largest size, in [StepSize] steps down
     * from [maxFontSize] and never below [MinFontSize], at which the name
     * lays out without ellipsis (see [fitsWithoutEllipsis]). The search is
     * in sp end to end - the only unit the floor is promised in - which is
     * what keeps it immune to the non-linear sp-to-dp curve that defeats
     * `TextAutoSize.StepBased` (see the class KDoc). Equality is by value,
     * as the `TextAutoSize` contract requires, so recomposition with the
     * same style does not invalidate the text layout.
     */
    fun autoSize(styleFontSize: TextUnit): TextAutoSize = TitleAutoSize(MinFontSize, maxFontSize(styleFontSize), StepSize)

    /**
     * The search [autoSize] runs, as a pure function: candidates from [max]
     * down to [min] in [step] steps (the ceiling itself first, so a name
     * that fits at full size costs one layout), the first that [fits]
     * wins, and when nothing fits the floor is returned - the auto-sizer
     * never shrinks past it; ellipsis takes over there. All three units
     * must be sp: that is the invariant the floor rests on.
     */
    fun largestFittingSize(
        min: TextUnit,
        max: TextUnit,
        step: TextUnit,
        fits: (TextUnit) -> Boolean,
    ): TextUnit {
        require(min.isSp && max.isSp && step.isSp) { "title auto-size works in sp only" }
        require(step.value > 0f) { "step must be positive" }
        var candidate = max.value
        while (candidate >= min.value) {
            val size = candidate.sp
            if (fits(size)) return size
            candidate -= step.value
        }
        return min
    }

    /**
     * What "fits" means to [autoSize]: the laid-out name was not ellipsised.
     * With [TextOverflow.Ellipsis] Compose ellipsises the last line it
     * keeps, whether the overflow was in width (the name needed more than
     * [MaxLines] lines) or in height (its lines did not fit [MaxHeight]
     * and the height-aware ellipsis dropped one), so one check covers both.
     * This is the same reading `TextAutoSize.StepBased` applies to an
     * ellipsised text; for any other overflow mode the visual overflow is
     * the answer.
     */
    fun fitsWithoutEllipsis(result: TextLayoutResult): Boolean =
        when (result.layoutInput.overflow) {
            TextOverflow.Ellipsis -> result.lineCount == 0 || !result.isLineEllipsized(result.lineCount - 1)
            else -> !result.hasVisualOverflow
        }

    /**
     * Whether [lines] lines of the name at [fontSize] fit [available] under
     * [density] - the height arithmetic as a pure function of font scale
     * (via [density]'s sp-to-dp conversion, which is non-linear above 1.03),
     * candidate size, line count and available height. A line is
     * [LineHeight] x the font's dp size, so `lines` lines are AT MOST that
     * many line heights: with [LineTrim] they are one line height plus one
     * glyph box, a little less, which makes this the conservative bound -
     * a size this passes certainly fits; the real layout may pass one step
     * more at the margin. Not consulted by [autoSize], which measures the
     * real layout; this is the model `ConversationTitleFitTest` holds the
     * auto-sizer's outcomes against.
     */
    fun linesFit(
        fontSize: TextUnit,
        lines: Int,
        density: Density,
        available: Dp = MaxHeight,
    ): Boolean = lineHeight(fontSize, density).value * lines <= available.value

    /** One line of the name at [fontSize] under [density]: [LineHeight] x the font's dp size. */
    fun lineHeight(
        fontSize: TextUnit,
        density: Density,
    ): Dp = with(density) { fontSize.toDp() * LineHeight.value }
}

/** See [ConversationTitleFit.autoSize]. A data class: `TextAutoSize` requires value equality. */
internal data class TitleAutoSize(
    val minFontSize: TextUnit,
    val maxFontSize: TextUnit,
    val stepSize: TextUnit,
) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(
        constraints: Constraints,
        text: AnnotatedString,
    ): TextUnit =
        ConversationTitleFit.largestFittingSize(minFontSize, maxFontSize, stepSize) { size ->
            ConversationTitleFit.fitsWithoutEllipsis(performLayout(constraints, text, size))
        }
}
