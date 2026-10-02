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
 * work; only [SendFailureReason.TRANSIENT] gets that hint - in particular
 * NOT [SendFailureReason.PDU_REJECTED], which the platform returns within
 * milliseconds and which a bare retry repeats exactly (issue #51). An SMS
 * failure carries a reason only when the platform's result code has a clear
 * meaning ([SendFailureReason.NO_SERVICE], [SendFailureReason.SHORT_CODE_BLOCKED]);
 * a generic SMS failure, or a row from before the column, reads honestly as
 * unexplained.
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
            SendFailureReason.PDU_REJECTED -> R.string.send_failure_pdu_rejected
            SendFailureReason.EXCEEDS_CARRIER_LIMIT -> R.string.send_failure_exceeds_carrier_limit
            SendFailureReason.CARRIER_DISABLED -> R.string.send_failure_carrier_disabled
            SendFailureReason.SIM_UNAVAILABLE -> R.string.send_failure_sim_unavailable
            SendFailureReason.NO_SERVICE -> R.string.send_failure_no_service
            SendFailureReason.SHORT_CODE_BLOCKED -> R.string.send_failure_short_code_blocked
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
            SendFailureReason.PDU_REJECTED -> R.string.conversation_not_sent_pdu_rejected
            SendFailureReason.EXCEEDS_CARRIER_LIMIT -> R.string.conversation_not_sent_exceeds_carrier_limit
            SendFailureReason.CARRIER_DISABLED -> R.string.conversation_not_sent_carrier_disabled
            SendFailureReason.SIM_UNAVAILABLE -> R.string.conversation_not_sent_sim_unavailable
            SendFailureReason.NO_SERVICE -> R.string.conversation_not_sent_no_service
            SendFailureReason.SHORT_CODE_BLOCKED -> R.string.conversation_not_sent_short_code_blocked
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
