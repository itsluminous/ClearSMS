package app.clearsms.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Visibility rule for the compose bar's accent-strip (é→e) button
 * (GitHub #17): it appears exactly when folding accents would send FEWER
 * billable segments, and never for an MMS (attachments staged), where
 * SMS encoding does not exist. Synthetic fixtures only.
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
}
