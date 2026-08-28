package app.clearsms.ui.conversation

import app.clearsms.R
import app.clearsms.domain.model.SubCategory
import app.clearsms.ui.components.BodyLinkKind

/**
 * Decides whether acting on a link in a message needs confirmation, and what
 * to warn about.
 *
 * Only a message flagged as a likely scam confirms. Links are precisely how
 * those messages do their damage, so leaving the app deserves a deliberate
 * second tap; anything else - a courier's tracking link, an agent's phone
 * number - acts directly, and an unclassified message is NOT treated as
 * suspicious (that would put a dialog in front of most personal messages).
 *
 * The warning names the actual risk, because "open a link" undersells a
 * payment: a scam UPI tap moves money, and a scam phone number puts a caller
 * in a position to ask for an OTP.
 */
object ScamLinkGate {
    fun confirmBeforeOpening(subCategory: SubCategory?): Boolean = subCategory == SubCategory.SCAM

    /** Dialog title for the pending action. */
    fun titleRes(kind: BodyLinkKind): Int =
        when (kind) {
            BodyLinkKind.PAYMENT -> R.string.link_scam_title_payment
            BodyLinkKind.PHONE -> R.string.link_scam_title_call
            else -> R.string.link_scam_title
        }

    /** Dialog body, which spells out what the tap would enable. */
    fun bodyRes(kind: BodyLinkKind): Int =
        when (kind) {
            BodyLinkKind.PAYMENT -> R.string.link_scam_body_payment
            BodyLinkKind.PHONE -> R.string.link_scam_body_call
            else -> R.string.link_scam_body
        }

    /** Confirm-button label for the pending action. */
    fun confirmRes(kind: BodyLinkKind): Int =
        when (kind) {
            BodyLinkKind.PAYMENT -> R.string.link_scam_pay
            BodyLinkKind.PHONE -> R.string.link_scam_call
            else -> R.string.link_scam_open
        }
}
