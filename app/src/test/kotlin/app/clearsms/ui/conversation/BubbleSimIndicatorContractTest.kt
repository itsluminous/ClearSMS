package app.clearsms.ui.conversation

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Source-level contract for the bubble SIM mark (GitHub #109), in the
 * [DeliveryTickContractTest] style because the repo has no Compose UI
 * harness: the bubble and the "More details" dialog resolve a message's
 * SIM through ONE rule ([MessageDetails.simRowFor]); the mark is rendered
 * in exactly one place, beside the time label and BEFORE the delivery
 * tick, so the sent/delivered tick remains the last icon on the row;
 * it draws the compose bar's own SIM outline; it is gated on the live
 * SIM list the dialog uses; and it is announced as the SIM's label, never
 * as a bare glyph. No new string: the label is the shared [app.clearsms.sms.SimLabel] text.
 */
class BubbleSimIndicatorContractTest {
    private fun source(path: String) = File("src/main/kotlin/app/clearsms/$path").readText()

    @Test
    fun `the bubble resolves the SIM through the details dialog's rule - never its own`() {
        val decision = source("ui/conversation/BubbleSimIndicator.kt")
        assertThat(decision).contains("return MessageDetails.simRowFor(activeSims, subscriptionId)")
        assertThat(decision).contains("): MessageDetails.Row.Sim?")
        // The one extra gate is the compose bar's dual-SIM gate, by name.
        assertThat(decision).contains("if (!SimSelector.indicatorVisible(activeSims)) return null")
        // No second subscription lookup, no hand-rolled slot arithmetic.
        assertThat(decision).doesNotContain("firstOrNull")
        assertThat(decision).doesNotContain("slotIndex")
        assertThat(decision).doesNotContain("slotLabelFor")
        assertThat(decision).doesNotContain("slotNumberFor")
    }

    @Test
    fun `rendered once, in the time row before the tick, from the pure decision`() {
        val screen = source("ui/conversation/ConversationScreen.kt")
        assertThat(screen).contains("sim = BubbleSimIndicators.forItem(item, activeSims),")
        // Same row as the time label and the tick, directly BEFORE the tick:
        // the delivery tick is deliberately the last icon on the row.
        // Anchored on the call site's own argument, not a file-global
        // indexOf: the composables are also DECLARED in this file, and the
        // declarations would satisfy a naive ordering check.
        val betweenIcons =
            screen
                .substringAfter("sim = BubbleSimIndicators.forItem(item, activeSims),")
                .substringBefore("tick = DeliveryTicks.tickFor(item),")
        assertThat(betweenIcons.length).isLessThan(200)
        assertWithMessage("the delivery tick must be the LAST icon on the time row")
            .that(betweenIcons)
            .contains("DeliveryTickIcon(")
        // Exactly one render site.
        assertThat(screen.split("BubbleSimIcon(").size - 1).isEqualTo(2) // call + definition
        assertThat(screen).contains("fun BubbleSimIcon(")
        // Nobody else calls the decision.
        val root = File("src/main/kotlin/app/clearsms")
        val callers =
            root
                .walkTopDown()
                .filter { it.extension == "kt" && it.name != "BubbleSimIndicator.kt" }
                .filter { "BubbleSimIndicators." in it.readText() }
                .map { it.name }
                .toList()
        assertThat(callers).containsExactly("ConversationScreen.kt")
    }

    @Test
    fun `gated on the live SIM list the dialog uses - the same state field`() {
        val screen = source("ui/conversation/ConversationScreen.kt")
        val bubble = screen.substringAfter("private fun MessageBubble(").substringBefore(") {")
        assertThat(bubble).contains("activeSims: List<SimInfo> = emptyList(),")
        // The call site hands the bubble the ViewModel's list - never a
        // system service of its own (the dialog's contract holds the same).
        val callSite = screen.substringAfter("MessageBubble(\n").substringBefore("onClick = {")
        assertThat(callSite).contains("activeSims = state.activeSims,")
        assertThat(screen).doesNotContain("SubscriptionManager")
    }

    @Test
    fun `null renders nothing before the spacer, the icon is the bar's SIM outline, spoken as the SIM label`() {
        val screen = source("ui/conversation/ConversationScreen.kt")
        val icon = screen.substringAfter("fun BubbleSimIcon(")
        // The early return precedes the Spacer: a single-SIM phone's bubble
        // is byte-for-byte what it was - no icon, no spacing change.
        assertThat(icon.substringBefore("Spacer(")).contains("if (sim == null) return")
        // One SIM glyph across the app: the compose bar's outline, now internal.
        assertThat(icon).contains("imageVector = SimOutlineGlyph,")
        val bar = source("ui/components/MessageComposerBar.kt")
        assertThat(bar).contains("internal val SimOutlineGlyph: ImageVector by lazy {")
        // The slot digit is drawn inside it, from the row - no "SIM $n" literal.
        assertThat(icon).contains("text = sim.slot.toString(),")
        // Accessibility: the SIM's own label ("SIM 1 - Airtel"), identical to
        // the details row, and the decorative digit stays out of the tree.
        assertThat(icon).contains("contentDescription = sim.label,")
        assertThat(icon).contains("modifier = Modifier.clearAndSetSemantics {},")
        // sp -> dp through LocalDensity, so it scales with the font like the tick.
        assertThat(icon).contains("MaterialTheme.typography.labelSmall.fontSize")
        assertThat(icon).contains("LocalDensity.current")
        // Same on-bubble tint as the time label and the tick.
        assertThat(icon).contains("tint = tint,")
        assertThat(screen.substringAfter("sim = BubbleSimIndicators.forItem(item, activeSims),"))
            .startsWith("\n                                tint = textColor.copy(alpha = 0.7f),")
    }
}
