package app.clearsms.ui.conversation

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

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
        // And the screen feeds THAT floor to the auto-sizer, no literal of its own.
        val text = nameText()
        assertThat(text).contains("TextAutoSize.StepBased(")
        assertThat(text).contains("minFontSize = ConversationTitleFit.MinFontSize,")
        assertThat(text).contains("stepSize = ConversationTitleFit.StepSize,")
        assertThat(text).doesNotContainMatch("minFontSize = \\d")
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
        assertThat(nameText()).contains("maxFontSize = ConversationTitleFit.maxFontSize(titleStyle.fontSize),")
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

    @Test
    fun `two lines fit the fixed bar at font scale 1, larger scales shrink first and keep to the lines that fit`() {
        // TopAppBar's container is 64dp and does not grow; the name's layout
        // is capped there so Compose's height-aware ellipsis drops lines
        // rather than the bar clipping them.
        assertThat(ConversationTitleFit.MaxHeight).isEqualTo(64.dp)
        assertThat(nameText()).contains(".heightIn(max = ConversationTitleFit.MaxHeight)")
        // The line height is 1.2 x the auto-sized font (see the test above),
        // so the height question is font size x font scale. Two lines are
        // AT MOST 2 x line height: with Trim.Both they are one line height
        // plus one glyph box, which is smaller still, so these figures are
        // the conservative bound.
        val ratio = ConversationTitleFit.LineHeight.value
        val ceilingSp = ConversationTitleFit.maxFontSize(22.sp).value
        val floorSp = ConversationTitleFit.MinFontSize.value
        val maxHeight = ConversationTitleFit.MaxHeight.value

        fun lineHeightDp(
            fontSp: Float,
            fontScale: Float,
        ) = ratio * fontSp * fontScale

        fun linesThatFit(
            fontSp: Float,
            fontScale: Float,
        ) = (maxHeight / lineHeightDp(fontSp, fontScale)).toInt().coerceAtMost(ConversationTitleFit.MaxLines)
        // At scale 1.0 the ceiling's two lines take 52.8dp of 64dp (11.2dp
        // to spare; the old fixed 28sp line left only 4dp).
        assertThat(lineHeightDp(ceilingSp, 1.0f)).isWithin(0.01f).of(26.4f)
        assertThat(linesThatFit(ceilingSp, 1.0f)).isEqualTo(2)
        // At 1.15 two lines now fit at the FULL title size (60.7dp); with
        // the fixed 28sp line they no longer did (64.4dp).
        assertThat(linesThatFit(ceilingSp, 1.15f)).isEqualTo(2)
        assertThat(28f * 2 * 1.15f).isGreaterThan(maxHeight)
        // At 1.3 the ceiling's two lines overflow (68.6dp), so the
        // auto-sizer shrinks - two lines fit from 20sp down (62.4dp), well
        // above the floor - instead of dropping a line at once.
        assertThat(linesThatFit(ceilingSp, 1.3f)).isEqualTo(1)
        assertThat(linesThatFit(20f, 1.3f)).isEqualTo(2)
        assertThat(20f).isAtLeast(floorSp)
        // Two lines keep fitting, at the floor, up to scale 1.66; past it
        // (Android's 2.0 maximum included) even the floor's two lines
        // overflow (76.8dp), so ellipsis keeps the name to one line rather
        // than shrinking below the floor.
        assertThat(linesThatFit(floorSp, 1.66f)).isEqualTo(2)
        assertThat(linesThatFit(floorSp, 1.67f)).isEqualTo(1)
        assertThat(linesThatFit(floorSp, 2.0f)).isEqualTo(1)
        // Even at 2.0 one line of the FULL title size fits (52.8dp), so the
        // name is never dropped entirely.
        assertThat(lineHeightDp(ceilingSp, 2.0f)).isAtMost(maxHeight)
        // What the floor means at those scales: the smallest rendered glyph
        // is floor x scale, so a large-font user is never handed less than
        // their setting's share of 16sp - the auto-sizer stops there and
        // ellipsises instead.
        val floorDp = { scale: Float -> floorSp * scale }
        assertThat(floorDp(1.3f)).isWithin(0.01f).of(20.8f)
        assertThat(floorDp(2.0f)).isWithin(0.01f).of(32f)
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
