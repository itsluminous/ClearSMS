package app.clearsms.ui.conversation

import app.clearsms.domain.model.SubCategory
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Whether a tapped link opens straight away or asks first. Links are how a
 * scam message does its damage, so one flagged as a likely scam confirms
 * before leaving the app - while ordinary messages (a courier tracking link,
 * a carrier's deactivation page) open without ceremony.
 */
class ScamLinkGateTest {
    @Test
    fun `a link in a scam-flagged message asks first`() {
        assertThat(ScamLinkGate.confirmBeforeOpening(SubCategory.SCAM)).isTrue()
    }

    @Test
    fun `ordinary messages open their links directly`() {
        for (sub in SubCategory.entries.filterNot { it == SubCategory.SCAM }) {
            assertThat(ScamLinkGate.confirmBeforeOpening(sub)).isFalse()
        }
    }

    @Test
    fun `an unclassified message opens its links directly`() {
        // No sub-category is not evidence of a scam; treating it as one would
        // put a dialog in front of most personal messages.
        assertThat(ScamLinkGate.confirmBeforeOpening(null)).isFalse()
    }
}
