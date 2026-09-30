package app.clearsms.ui.conversation

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
    fun `two lines fit the fixed bar at font scale 1, larger scales keep to the lines that fit`() {
        // TopAppBar's container is 64dp and does not grow; the name's layout
        // is capped there so Compose's height-aware ellipsis drops lines
        // rather than the bar clipping them.
        assertThat(ConversationTitleFit.MaxHeight).isEqualTo(64.dp)
        assertThat(nameText()).contains(".heightIn(max = ConversationTitleFit.MaxHeight)")
        // titleLarge's line height is 28sp; auto-size changes the font, not
        // the line height, so the height question is purely font scale.
        val lineHeightSp = 28f

        fun linesThatFit(fontScale: Float) =
            (ConversationTitleFit.MaxHeight.value / (lineHeightSp * fontScale)).toInt().coerceAtMost(ConversationTitleFit.MaxLines)
        assertThat(linesThatFit(1.0f)).isEqualTo(2) // 56dp of 64dp
        assertThat(linesThatFit(1.15f)).isEqualTo(1) // 64.4dp: two no longer fit
        assertThat(linesThatFit(1.3f)).isEqualTo(1) // 72.8dp -> one 36.4dp line
        assertThat(linesThatFit(2.0f)).isEqualTo(1) // 112dp -> one 56dp line
        // Even at Android's 2.0 maximum one line always fits, so the name is
        // never dropped entirely.
        assertThat(lineHeightSp * 2.0f).isAtMost(ConversationTitleFit.MaxHeight.value)
        // What the floor means at those scales: the smallest rendered glyph
        // is floor x scale, so a large-font user is never handed less than
        // their setting's share of 16sp.
        val floorDp = { scale: Float -> ConversationTitleFit.MinFontSize.value * scale }
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
        // Style and colour come from the bar's title slot, so BasicText is
        // indistinguishable from the Material Text it replaced.
        val text = nameText()
        assertThat(slot).contains("val titleStyle = LocalTextStyle.current")
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
