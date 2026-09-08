package app.clearsms.ui.components

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Visibility rule for the compose bar's accent-strip (è) button
 * (GitHub #17): it appears exactly when folding accents would send FEWER
 * billable segments, and never for an MMS (attachments staged), where
 * SMS encoding does not exist. Synthetic fixtures only.
 *
 * Plus the sizing contract: the affordance is a single bold `è` at
 * EXACTLY the SIM indicator's footprint, both drawn from the shared
 * [ComposeBarIndicatorMetrics] so a future edit cannot quietly re-inflate
 * the accent button into the old wide "è → e" chip.
 */
class AccentStripAffordanceTest {
    // One č flips 100 plain letters from 1 GSM segment to 2 UCS-2 segments.
    private val savingDraft = "č" + "a".repeat(100)

    @Test
    fun `visible when folding reduces the segment count`() {
        val plan = accentFoldPlan(savingDraft, attachmentCount = 0)
        assertThat(plan).isNotNull()
        assertThat(plan!!.segmentsBefore).isEqualTo(2)
        assertThat(plan.segmentsAfter).isEqualTo(1)
    }

    @Test
    fun `hidden for plain text`() {
        assertThat(accentFoldPlan("hello there, plain draft", attachmentCount = 0)).isNull()
    }

    @Test
    fun `hidden when folding would change text for no gain`() {
        // Short accented drafts already fit one segment either way.
        assertThat(accentFoldPlan("ahoj číslo", attachmentCount = 0)).isNull()
        // An emoji pins the message to UCS-2 regardless of the č.
        assertThat(accentFoldPlan("č \uD83D\uDE00 " + "a".repeat(100), attachmentCount = 0)).isNull()
    }

    @Test
    fun `hidden with attachments staged - the message goes as MMS`() {
        assertThat(accentFoldPlan(savingDraft, attachmentCount = 1)).isNull()
    }

    @Test
    fun `hidden for blank drafts`() {
        assertThat(accentFoldPlan("", attachmentCount = 0)).isNull()
    }

    @Test
    fun `tapping the affordance twice cannot double-fold`() {
        // The plan's folded text is pure GSM, so a second plan on it is null.
        val folded = accentFoldPlan(savingDraft, attachmentCount = 0)!!.folded
        assertThat(accentFoldPlan(folded, attachmentCount = 0)).isNull()
    }

    // ---- Sizing contract (source scan, the repo's convention-test pattern) ----

    private val bar = File("src/main/kotlin/app/clearsms/ui/components/MessageComposerBar.kt").readText()
    private val accentBlock = bar.substringAfter("if (foldPlan != null").substringBefore("// Compact SIM indicator")
    private val simBlock = bar.substringAfter("if (sim.visible)").substringBefore("// Send:")

    @Test
    fun `the affordance glyph is a single bold e-grave - no arrow, no second letter, no chip`() {
        assertThat(accentBlock).contains("text = \"è\"")
        assertThat(accentBlock).doesNotContain("→")
        assertThat(accentBlock).doesNotContain("é")
        assertThat(accentBlock).contains("fontWeight = FontWeight.Bold")
        // The è→e explanation lives in the long-press hint string instead.
        assertThat(accentBlock).contains("compose_strip_accents_hint")
    }

    @Test
    fun `the affordance sits at exactly the SIM indicator's footprint via the shared metrics`() {
        assertThat(accentBlock).contains("Modifier.size(ComposeBarIndicatorMetrics.IconSize)")
        assertThat(accentBlock).contains("fontSize = ComposeBarIndicatorMetrics.GlyphFontSize")
        // No eyeballed sizes may creep back in: every size in the block goes
        // through the shared constants (14.dp is the ripple clip both
        // neighbours use; 6.dp the shared tap padding).
        val literalSizes =
            Regex("""\b(\d+(?:\.\d+)?)\.(dp|sp)\b""")
                .findAll(accentBlock)
                .map { it.value }
                .filterNot { it == "14.dp" || it == "6.dp" }
                .toList()
        assertThat(literalSizes).isEmpty()
    }

    @Test
    fun `the SIM indicator draws from the same shared metrics, so the two cannot drift`() {
        // The SIM outline's intrinsic size and slot digit are the constants'
        // other consumer - resizing one side alone now fails here.
        assertThat(bar).contains("defaultWidth = ComposeBarIndicatorMetrics.IconSize")
        assertThat(bar).contains("defaultHeight = ComposeBarIndicatorMetrics.IconSize")
        assertThat(simBlock).contains("fontSize = ComposeBarIndicatorMetrics.GlyphFontSize")
    }

    @Test
    fun `shared metrics pin the footprint values`() {
        assertThat(ComposeBarIndicatorMetrics.IconSize).isEqualTo(24.dp)
        assertThat(ComposeBarIndicatorMetrics.GlyphFontSize).isEqualTo(12.sp)
    }

    @Test
    fun `the affordance keeps tap, long-press hint and an accessibility description`() {
        assertThat(accentBlock).contains("combinedClickable")
        assertThat(accentBlock).contains("onAccentsStripped(foldPlan)")
        assertThat(accentBlock).contains("onLongClickLabel = stripHint")
        assertThat(accentBlock).contains("semantics { contentDescription = stripHint }")
    }
}
