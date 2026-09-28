package app.clearsms.ui.conversation

import androidx.annotation.StringRes
import app.clearsms.R
import app.clearsms.mms.SendFailureReason

/**
 * The ONE mapping from a persisted [SendFailureReason] to what the user
 * reads about it - used by the bubble's status line, the Retry dialog and
 * the "More details" Error row, so the three never disagree.
 *
 * Wording rules: say what the phone reported and no more. The app cannot
 * see inside the carrier's network, so "the MMS connection did not come
 * up" is stated as such - never diagnosed as "your data is off" or "the
 * carrier is down". A structural reason ([SendFailureReason.NO_MMS_NETWORK],
 * [SendFailureReason.CARRIER_DISABLED]) is never told that a retry will
 * work; only [SendFailureReason.TRANSIENT] gets that hint. A failure
 * recorded without a reason (an SMS failure, or a row from before the
 * column) reads honestly as unexplained.
 *
 * One optional addendum exists: when a failed MMS went out on a SIM other
 * than the phone's mobile-data SIM, [dataSimHintRes] is appended to the
 * explanation - as guidance ("may only work"), never a verdict. WHETHER it
 * applies is decided in one place, [app.clearsms.mms.DataSim.hintFor]; the
 * bubble's short label never carries it.
 */
object SendFailureText {
    /** Full explanation, one or two sentences: the dialog and details row. */
    @StringRes
    fun explanationRes(reason: SendFailureReason?): Int =
        when (reason) {
            SendFailureReason.NO_MMS_NETWORK -> R.string.send_failure_no_mms_network
            SendFailureReason.APN_CONFIGURATION -> R.string.send_failure_apn
            SendFailureReason.HTTP_FAILURE -> R.string.send_failure_http
            SendFailureReason.TRANSIENT -> R.string.send_failure_transient
            SendFailureReason.CARRIER_DISABLED -> R.string.send_failure_carrier_disabled
            SendFailureReason.SIM_UNAVAILABLE -> R.string.send_failure_sim_unavailable
            SendFailureReason.DISPATCH_FAILED -> R.string.send_failure_dispatch
            SendFailureReason.UNKNOWN, null -> R.string.send_failure_unknown
        }

    /**
     * The bubble's status line: "Not sent" plus the reason in a few words,
     * or the bare "Not sent" when nothing specific is known.
     */
    @StringRes
    fun bubbleLabelRes(reason: SendFailureReason?): Int =
        when (reason) {
            SendFailureReason.NO_MMS_NETWORK -> R.string.conversation_not_sent_no_mms_network
            SendFailureReason.APN_CONFIGURATION -> R.string.conversation_not_sent_apn
            SendFailureReason.HTTP_FAILURE -> R.string.conversation_not_sent_http
            SendFailureReason.TRANSIENT -> R.string.conversation_not_sent_transient
            SendFailureReason.CARRIER_DISABLED -> R.string.conversation_not_sent_carrier_disabled
            SendFailureReason.SIM_UNAVAILABLE -> R.string.conversation_not_sent_sim_unavailable
            SendFailureReason.DISPATCH_FAILED -> R.string.conversation_not_sent_dispatch
            SendFailureReason.UNKNOWN, null -> R.string.conversation_not_sent
        }

    /** Whether the explanation for [reason] invites a retry (only genuinely transient trouble does). */
    fun suggestsRetry(reason: SendFailureReason?): Boolean = reason == SendFailureReason.TRANSIENT

    /**
     * The data-SIM addendum, formatted with the sending slot then the data
     * slot ([app.clearsms.mms.DataSimHint.sendingSlot], [app.clearsms.mms.DataSimHint.dataSlot]).
     */
    @StringRes
    fun dataSimHintRes(): Int = R.string.send_failure_data_sim_hint
}
