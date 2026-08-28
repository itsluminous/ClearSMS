package app.clearsms.ui.conversation

import app.clearsms.domain.model.SubCategory

/**
 * Decides whether tapping a link in a message needs confirmation first.
 *
 * Only a message flagged as a likely scam does. Links are precisely how those
 * messages do their damage, so leaving the app deserves a deliberate second
 * tap; anything else - a courier's tracking link, a carrier's settings page -
 * opens directly, and an unclassified message is NOT treated as suspicious
 * (that would put a dialog in front of most personal messages).
 */
object ScamLinkGate {
    fun confirmBeforeOpening(subCategory: SubCategory?): Boolean = subCategory == SubCategory.SCAM
}
