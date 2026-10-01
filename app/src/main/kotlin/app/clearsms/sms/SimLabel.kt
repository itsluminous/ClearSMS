package app.clearsms.sms

/**
 * The ONE place a SIM is named to the user: slot FIRST, carrier second
 * ("SIM 1 - Airtel"). Chosen for GitHub #7, whose reporter had two SIMs on
 * the SAME carrier: the name alone was useless, the slot never is. Both the
 * compose bar ([app.clearsms.ui.components.SimUiState]: tap toast,
 * long-press hint, accessibility description) and the "More details"
 * dialog's SIM row ([app.clearsms.ui.conversation.MessageDetails.Row.Sim])
 * format through here, so the two surfaces cannot drift - a contract test
 * pins that.
 *
 * The name is the platform's SubscriptionInfo display name (the user's
 * nickname when one is set, else the carrier). A blank name degrades to
 * the bare slot - never a dangling " - ".
 */
object SimLabel {
    /** "SIM 1 - Airtel", or just "SIM 1" when [operatorName] is blank. */
    fun slotFirst(
        slot: Int,
        operatorName: String,
    ): String = "SIM $slot${nameSuffix(operatorName)}"

    /** " - Airtel" for a known name, "" for a blank one (appended after the slot phrase). */
    fun nameSuffix(operatorName: String): String = if (operatorName.isBlank()) "" else " - $operatorName"
}
