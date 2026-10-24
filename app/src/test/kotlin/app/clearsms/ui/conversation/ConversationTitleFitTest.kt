package app.clearsms.ui.conversation

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import kotlin.math.floor

/**
 * Pins how the sender name fits the fixed-height conversation top bar (see
 * [ConversationTitleFit]): two lines at most, shrinking no further than an
 * sp floor, ellipsis for the rest; and that the screen wires exactly that
 * into the ONE title text, which stays a single tap target. No Compose UI
 * harness here (repo convention), so the layout facts are pure arithmetic
 * over the contract's values plus a source contract on the wiring.
 */
class ConversationTitleFitTest {
    private fun source(path: String) = File("src/main/kotlin/app/clearsms/$path").readText()

    private fun titleSlot(): String {
        val conversation = source("ui/conversation/ConversationScreen.kt")
        return conversation.substring(
            conversation.indexOf("TopAppBar(\n                    title = {"),
            conversation.indexOf("navigationIcon = {"),
        )
    }

    private fun nameText(): String {
        val slot = titleSlot()
        return slot.substring(slot.indexOf("BasicText("), slot.indexOf("if (state.muted) {"))
    }

    @Test
    fun `the name is bounded to two lines with ellipsis overflow`() {
        assertThat(ConversationTitleFit.MaxLines).isEqualTo(2)
        val text = nameText()
        assertThat(text).contains("maxLines = ConversationTitleFit.MaxLines,")
        assertThat(text).contains("overflow = TextOverflow.Ellipsis,")
    }

    @Test
    fun `the auto-size floor is a hard sp minimum that a future edit cannot lower past body text`() {
        // 16sp is Material3 titleMedium / bodyLarge: the smallest text the
        // app runs. Pinned exactly - lowering it would let a long name shrink
        // to metadata size, and an sp (not dp) floor is what keeps the user's
        // font-scale setting in charge of the minimum.
        assertThat(ConversationTitleFit.MinFontSize).isEqualTo(16.sp)
        assertThat(ConversationTitleFit.MinFontSize.type).isEqualTo(16.sp.type)
        assertThat(ConversationTitleFit.StepSize).isEqualTo(1.sp)
        // And the screen draws with THE contract's auto-sizer - built from
        // the bar's style, no literal of its own - which carries that floor.
        val text = nameText()
        assertThat(text).contains("autoSize = ConversationTitleFit.autoSize(titleStyle.fontSize),")
        assertThat(text).doesNotContainMatch("minFontSize = \\d")
        val sizer = ConversationTitleFit.autoSize(22.sp) as TitleAutoSize
        assertThat(sizer.minFontSize).isEqualTo(ConversationTitleFit.MinFontSize)
        assertThat(sizer.stepSize).isEqualTo(ConversationTitleFit.StepSize)
        // Not TextAutoSize.StepBased: its px-grid search cannot reach the
        // ceiling under non-linear font scaling (see the test below), so the
        // title must never go back to it.
        assertThat(titleSlot()).doesNotContain("TextAutoSize.StepBased(")
        // Value equality, as TextAutoSize requires: the same style yields an
        // equal sizer, so recomposition does not invalidate the text layout.
        assertThat(ConversationTitleFit.autoSize(22.sp)).isEqualTo(ConversationTitleFit.autoSize(22.sp))
        assertThat(ConversationTitleFit.autoSize(22.sp).hashCode()).isEqualTo(ConversationTitleFit.autoSize(22.sp).hashCode())
        assertThat(ConversationTitleFit.autoSize(22.sp)).isNotEqualTo(ConversationTitleFit.autoSize(28.sp))
    }

    @Test
    fun `the ceiling is the bar's own title style, never below the floor`() {
        // The style TopAppBar provides (titleLarge, 22sp): a short name is
        // exactly the Text it was before.
        assertThat(ConversationTitleFit.maxFontSize(22.sp)).isEqualTo(22.sp)
        assertThat(ConversationTitleFit.maxFontSize(28.sp)).isEqualTo(28.sp)
        // Unspecified / non-sp styles fall back to titleLarge rather than
        // producing an unusable range.
        assertThat(ConversationTitleFit.maxFontSize(TextUnit.Unspecified)).isEqualTo(22.sp)
        assertThat(ConversationTitleFit.maxFontSize(1.5.em)).isEqualTo(22.sp)
        // A style smaller than the floor cannot be used to sneak under it.
        assertThat(ConversationTitleFit.maxFontSize(12.sp)).isEqualTo(ConversationTitleFit.MinFontSize)
        // The auto-sizer's ceiling IS maxFontSize of the style it is built from.
        assertThat((ConversationTitleFit.autoSize(22.sp) as TitleAutoSize).maxFontSize).isEqualTo(22.sp)
        assertThat((ConversationTitleFit.autoSize(28.sp) as TitleAutoSize).maxFontSize).isEqualTo(28.sp)
        assertThat((ConversationTitleFit.autoSize(TextUnit.Unspecified) as TitleAutoSize).maxFontSize).isEqualTo(22.sp)
        assertThat((ConversationTitleFit.autoSize(12.sp) as TitleAutoSize).maxFontSize).isEqualTo(ConversationTitleFit.MinFontSize)
    }

    @Test
    fun `the line height follows the auto-sized font, not titleLarge's fixed 28sp line`() {
        // TextAutoSize.StepBased shrinks fontSize and leaves lineHeight as
        // declared, so an sp line height is wrong at every step but the
        // ceiling: 28sp over a 16sp floor is a 1.75 ratio, and the two
        // halves of one name read as two names. An em line height is
        // resolved against the chosen font, so the ratio is a constant.
        val ratio = ConversationTitleFit.LineHeight
        assertThat(ratio.isEm).isTrue()
        assertThat(ratio.value).isWithin(0.001f).of(1.2f)
        // Tighter than titleLarge's own 28/22 (one name, not two
        // paragraphs) but never below Roboto's ~1.17em glyph box, so
        // descenders cannot touch the next line's capitals.
        assertThat(ratio.value).isLessThan(28f / 22f)
        assertThat(ratio.value).isAtLeast(1.17f)
        // The ratio holds at every auto-size step the floor and ceiling allow.
        for (fontSp in 16..22) {
            val lineSp = ratio.value * fontSp
            assertThat(lineSp / fontSp).isWithin(0.001f).of(1.2f)
            // ...where the old fixed line grew wronger the more the name shrank.
            assertThat(28f / fontSp).isAtLeast(28f / 22f)
        }

        // Trim both ends: the half-leading above the first line and below
        // the last is dropped, and what is left between the lines splits in
        // the font's ascent:descent proportion. Pinned to Compose's own
        // default for a style without includeFontPadding, which is what the
        // title already got - a future theme that sets lineHeightStyle
        // cannot quietly float the first line lower in the bar.
        val lineHeightStyle = ConversationTitleFit.LineTrim
        assertThat(lineHeightStyle.trim).isEqualTo(LineHeightStyle.Trim.Both)
        assertThat(lineHeightStyle.alignment).isEqualTo(LineHeightStyle.Alignment.Proportional)
        assertThat(lineHeightStyle).isEqualTo(LineHeightStyle.Default)

        // style() replaces ONLY the line metrics: the bar's font, weight,
        // size and everything else survive untouched, which is what keeps a
        // short name exactly the Text it was (with Trim.Both a one-line
        // name is its glyph box tall whatever the line height, so the
        // change cannot reach it).
        val bar =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                lineHeight = 28.sp,
            )
        val styled = ConversationTitleFit.style(bar)
        assertThat(styled.lineHeight).isEqualTo(ratio)
        assertThat(styled.lineHeightStyle).isEqualTo(lineHeightStyle)
        assertThat(styled.fontSize).isEqualTo(22.sp)
        assertThat(styled.copy(lineHeight = bar.lineHeight, lineHeightStyle = bar.lineHeightStyle)).isEqualTo(bar)
        // Unspecified font sizes stay unspecified: style() never invents a size.
        assertThat(ConversationTitleFit.style(TextStyle()).fontSize).isEqualTo(TextUnit.Unspecified)
        // And the screen draws with THAT style, built from the bar's own.
        val slot = titleSlot()
        assertThat(slot).contains("val titleStyle = ConversationTitleFit.style(LocalTextStyle.current)")
        assertThat(nameText()).contains("style = titleStyle,")
        assertThat(nameText()).doesNotContainMatch("lineHeight\\s*=")
    }

    /**
     * Compose's own sp-to-dp conversion for a font scale: linear at 1.0,
     * and from 1.03 up the platform's non-linear curve (Compose carries the
     * tables itself, so this holds on every API level). The screen density
     * is irrelevant to dp arithmetic; 2.625 (a 420dpi phone) is used so px
     * figures in comments are reproducible.
     */
    private fun density(fontScale: Float) = Density(2.625f, fontScale)

    private fun spToDp(
        sp: Float,
        fontScale: Float,
    ): Float = with(density(fontScale)) { sp.sp.toDp().value }

    @Test
    fun `two lines fit the fixed bar at font scale 1, larger scales shrink first and keep to the lines that fit`() {
        // TopAppBar's container is 64dp and does not grow; the name's layout
        // is capped there so Compose's height-aware ellipsis drops lines
        // rather than the bar clipping them.
        assertThat(ConversationTitleFit.MaxHeight).isEqualTo(64.dp)
        assertThat(nameText()).contains(".heightIn(max = ConversationTitleFit.MaxHeight)")
        // The line height is 1.2 x the auto-sized font (see the test above),
        // so the height question is 1.2 x the font's dp size x lines. Two
        // lines are AT MOST 2 x line height: with Trim.Both they are one
        // line height plus one glyph box, which is smaller still, so
        // linesFit is the conservative bound (a size it passes certainly
        // fits; the real layout may pass one step more at the margin).
        //
        // The font's dp size is NOT sp x scale: Android's non-linear font
        // scaling (Compose applies the same curve on every API level, from
        // scale 1.03) grows large text less than small text - at 1.5, 16sp
        // is 23dp and 22sp is 27dp, not 24 and 33. All figures below come
        // from Compose's own conversion, never from a scale multiplication.
        val ceiling = ConversationTitleFit.maxFontSize(22.sp)
        val floor = ConversationTitleFit.MinFontSize

        fun fits(
            size: TextUnit,
            lines: Int,
            scale: Float,
        ) = ConversationTitleFit.linesFit(size, lines, density(scale))

        // Scale 1.0 is linear: the ceiling's line is 26.4dp and its two
        // lines take 52.8dp of 64dp (11.2dp to spare; the old fixed 28sp
        // line left only 4dp).
        assertThat(ConversationTitleFit.lineHeight(ceiling, density(1.0f)).value).isWithin(0.01f).of(26.4f)
        assertThat(fits(ceiling, 2, 1.0f)).isTrue()
        // The non-linear curve, pinned where the arithmetic depends on it.
        assertThat(spToDp(16f, 1.5f)).isWithin(0.01f).of(23f)
        assertThat(spToDp(22f, 1.5f)).isWithin(0.01f).of(27f)
        assertThat(spToDp(16f, 2.0f)).isWithin(0.01f).of(28f)
        assertThat(spToDp(22f, 2.0f)).isWithin(0.01f).of(35f)
        // A ONE-line name fits at the full title size at EVERY scale: even
        // at Android's 2.0 maximum its line is 42dp of 64. This is the
        // promise the defect broke - see the next test for how.
        for (scale in listOf(1.0f, 1.15f, 1.3f, 1.5f, 1.8f, 2.0f)) {
            assertWithMessage("one line at the ceiling, scale $scale").that(fits(ceiling, 1, scale)).isTrue()
        }
        assertThat(ConversationTitleFit.lineHeight(ceiling, density(2.0f)).value).isWithin(0.01f).of(42f)
        // Two lines keep fitting at the full title size through 1.3 (22sp is
        // 25dp there: 60dp of 64) - with linear scaling they would already
        // have overflowed at 1.3 (68.6dp), so large-font users keep the full
        // size longer than the old arithmetic claimed.
        assertThat(fits(ceiling, 2, 1.15f)).isTrue()
        assertThat(fits(ceiling, 2, 1.3f)).isTrue()
        assertThat(spToDp(22f, 1.3f)).isWithin(0.01f).of(25f)
        // At 1.5 the ceiling's two lines overflow (64.8dp), so the
        // auto-sizer shrinks - ONE step: two lines of 21sp (26.5dp) are
        // 63.6dp - well above the floor, instead of dropping a line at once.
        assertThat(fits(ceiling, 2, 1.5f)).isFalse()
        assertThat(fits(21.sp, 2, 1.5f)).isTrue()
        assertThat(21f).isAtLeast(floor.value)
        // Two lines keep fitting, at the floor, through 1.8 (16sp is 26dp
        // there: 62.4dp); at Android's 2.0 maximum even the floor's two
        // lines overflow (16sp is 28dp: 67.2dp), so ellipsis keeps the name
        // to one line rather than shrinking below the floor.
        assertThat(fits(floor, 2, 1.8f)).isTrue()
        assertThat(fits(floor, 2, 2.0f)).isFalse()
        // Even at 2.0 one line of the FULL title size fits (42dp), so the
        // name is never dropped entirely.
        assertThat(fits(ceiling, 1, 2.0f)).isTrue()
        // What the floor means at those scales: the smallest rendered glyph
        // is 16sp AS THE SETTING RENDERS IT, so a large-font user is never
        // handed less than their setting's share of 16sp - the auto-sizer
        // stops there and ellipsises instead.
        assertThat(spToDp(floor.value, 1.3f)).isWithin(0.01f).of(20.2f)
        assertThat(spToDp(floor.value, 2.0f)).isWithin(0.01f).of(28f)
        // The available height is a parameter so the bound is one function
        // of (scale, size, lines, height), not a 64dp special case.
        assertThat(ConversationTitleFit.linesFit(ceiling, 2, density(1.5f), available = 65.dp)).isTrue()
        assertThat(ConversationTitleFit.linesFit(ceiling, 2, density(1.5f), available = 64.dp)).isFalse()
    }

    /**
     * The search [ConversationTitleFit.autoSize] runs, held against the
     * height model at the three scales: a one-line name takes the ceiling
     * everywhere, a two-line name shrinks only when its two lines do not
     * fit, and never past the floor.
     */
    @Test
    fun `the auto-sizer picks the largest sp step that fits, from the ceiling down, never below the floor`() {
        val floor = ConversationTitleFit.MinFontSize
        val ceiling = ConversationTitleFit.maxFontSize(22.sp)
        val step = ConversationTitleFit.StepSize

        fun pick(
            lines: Int,
            scale: Float,
        ) = ConversationTitleFit.largestFittingSize(floor, ceiling, step) { size ->
            ConversationTitleFit.linesFit(size, lines, density(scale))
        }
        // One-line name: full size at every scale - the regression this file
        // guards (before the fix: 22sp at 1.0, but 20sp at 1.5 and 2.0).
        assertThat(pick(lines = 1, scale = 1.0f)).isEqualTo(22.sp)
        assertThat(pick(lines = 1, scale = 1.5f)).isEqualTo(22.sp)
        assertThat(pick(lines = 1, scale = 2.0f)).isEqualTo(22.sp)
        // Two-line name: full size at 1.0, one step down at 1.5, and at 2.0
        // nothing fits two lines so the floor is returned - the sizer never
        // goes under it; ellipsis takes over there.
        assertThat(pick(lines = 2, scale = 1.0f)).isEqualTo(22.sp)
        assertThat(pick(lines = 2, scale = 1.5f)).isEqualTo(21.sp)
        assertThat(pick(lines = 2, scale = 2.0f)).isEqualTo(floor)
        // Every candidate the search hands out is sp - the floor's unit.
        val seen = mutableListOf<TextUnit>()
        ConversationTitleFit.largestFittingSize(floor, ceiling, step) {
            seen += it
            false
        }
        assertThat(seen.map { it.value }).containsExactly(22f, 21f, 20f, 19f, 18f, 17f, 16f).inOrder()
        assertThat(seen.all { it.isSp }).isTrue()
        // The ceiling is tried first, so a name that fits at full size costs
        // one layout; the floor is returned without a further try.
        var tries = 0
        assertThat(
            ConversationTitleFit.largestFittingSize(floor, ceiling, step) {
                tries++
                true
            },
        ).isEqualTo(ceiling)
        assertThat(tries).isEqualTo(1)
        // Only sp is accepted: a dp or em bound would let the floor be
        // re-expressed in a unit the font setting does not scale.
        assertThrows(IllegalArgumentException::class.java) {
            ConversationTitleFit.largestFittingSize(floor, 1.5.em, step) { true }
        }
    }

    /**
     * WHY the title has its own sizer: `TextAutoSize.StepBased` searches in
     * PIXELS, and under non-linear font scaling that search cannot reach
     * the ceiling. This mirrors StepBased's algorithm (Compose foundation
     * 1.10: floor, ceiling and step converted to px, a binary search, the
     * result snapped to the px grid anchored at the floor, then one step
     * up if it still fits) against a predicate that says EVERYTHING fits,
     * so the only thing under test is which sizes the search can produce.
     */
    @Test
    fun `StepBased's px-grid search cannot reach the ceiling under non-linear font scaling - the defect`() {
        fun stepBasedMirror(scale: Float): Float {
            val density = density(scale)
            with(density) {
                val step = ConversationTitleFit.StepSize.toPx()
                val smallest = ConversationTitleFit.MinFontSize.toPx()
                val largest = ConversationTitleFit.maxFontSize(22.sp).toPx()
                var min = smallest
                var max = largest
                var current = (min + max) / 2
                while ((max - min) >= step) {
                    min = current // every size "fits"
                    current = (min + max) / 2
                }
                current = floor((min - smallest) / step) * step + smallest
                if (current + step <= largest) current += step
                return current.toSp().value
            }
        }
        // Linear scaling: the px grid is the sp grid and the ceiling is on it.
        assertThat(stepBasedMirror(1.0f)).isWithin(0.01f).of(22f)
        // Non-linear scaling: 16..22sp compresses to 23..27dp while the 1sp
        // step converts to 1.5dp, so the grid from the floor is 23, 24.5,
        // 26dp and the next point overshoots 27dp - 22sp is NEVER tried and
        // the 26dp point, 20sp, is returned for every name. (The measured
        // defect: a 10-character name at 20sp -> 26dp -> 68px -> a 49px cap
        // height on a 420dpi screen.)
        assertThat(stepBasedMirror(1.5f)).isWithin(0.01f).of(20f)
        assertThat(spToDp(20f, 1.5f)).isWithin(0.01f).of(26f)
        assertThat(stepBasedMirror(2.0f)).isWithin(0.01f).of(20f)
        // The contract's sizer reaches it at the same scales.
        for (scale in listOf(1.0f, 1.5f, 2.0f)) {
            val picked =
                ConversationTitleFit.largestFittingSize(
                    ConversationTitleFit.MinFontSize,
                    ConversationTitleFit.maxFontSize(22.sp),
                    ConversationTitleFit.StepSize,
                ) { true }
            assertWithMessage("scale $scale").that(picked).isEqualTo(22.sp)
        }
    }

    @Test
    fun `the name stays one tap target and the bar keeps its other occupants`() {
        val slot = titleSlot()
        // ONE clickable, on the Row that holds avatar + name + glyph - both
        // lines of the name are inside it. No second click target on the
        // text itself.
        assertThat(slot.split("clickable(").size - 1).isEqualTo(1)
        assertThat(slot.indexOf("Modifier.clickable(")).isLessThan(slot.indexOf("SenderAvatar("))
        assertThat(nameText()).doesNotContain("clickable")
        assertThat(slot).contains("onClickLabel = stringResource(R.string.conversation_sender_details)")
        // Style and colour come from the bar's title slot (the style with
        // only its line metrics swapped, see ConversationTitleFit.style), so
        // BasicText is indistinguishable from the Material Text it replaced.
        val text = nameText()
        assertThat(slot).contains("val titleStyle = ConversationTitleFit.style(LocalTextStyle.current)")
        assertThat(slot).contains("val titleColor = LocalContentColor.current")
        assertThat(text).contains("style = titleStyle,")
        assertThat(text).contains("color = { titleColor },")
        // Avatar before the name, glyph after it, exactly as before.
        assertThat(slot.indexOf("SenderAvatar(")).isLessThan(slot.indexOf("BasicText("))
        assertThat(slot.indexOf("BasicText(")).isLessThan(slot.indexOf("MutedIndicatorIcon("))
        // A service sender (no Call button) shares this title slot: the
        // call button is gated separately in actions, not in the title.
        assertThat(slot).doesNotContain("dialableSender")
    }
}
