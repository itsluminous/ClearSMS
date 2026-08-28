package app.clearsms.ui.conversation

import app.clearsms.domain.model.SubCategory
import app.clearsms.ui.components.BodyLinkKind
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

    @Test
    fun `each link kind warns about its own risk`() {
        // "Open a link" undersells a payment (it moves money) and a call (the
        // caller asks for an OTP), so the copy differs per kind.
        val titles = BodyLinkKind.entries.map { ScamLinkGate.titleRes(it) }.distinct()
        assertThat(titles).hasSize(3) // web+email share one, payment and phone differ

        assertThat(ScamLinkGate.titleRes(BodyLinkKind.PAYMENT))
            .isNotEqualTo(ScamLinkGate.titleRes(BodyLinkKind.WEB))
        assertThat(ScamLinkGate.titleRes(BodyLinkKind.PHONE))
            .isNotEqualTo(ScamLinkGate.titleRes(BodyLinkKind.WEB))
        assertThat(ScamLinkGate.titleRes(BodyLinkKind.EMAIL))
            .isEqualTo(ScamLinkGate.titleRes(BodyLinkKind.WEB))
    }

    @Test
    fun `every kind has a title, body and confirm label`() {
        for (kind in BodyLinkKind.entries) {
            assertThat(ScamLinkGate.titleRes(kind)).isNotEqualTo(0)
            assertThat(ScamLinkGate.bodyRes(kind)).isNotEqualTo(0)
            assertThat(ScamLinkGate.confirmRes(kind)).isNotEqualTo(0)
        }
    }

    @Test
    fun `the confirm label matches the action`() {
        assertThat(ScamLinkGate.confirmRes(BodyLinkKind.PHONE))
            .isNotEqualTo(ScamLinkGate.confirmRes(BodyLinkKind.WEB))
        assertThat(ScamLinkGate.confirmRes(BodyLinkKind.PAYMENT))
            .isNotEqualTo(ScamLinkGate.confirmRes(BodyLinkKind.WEB))
    }
}
