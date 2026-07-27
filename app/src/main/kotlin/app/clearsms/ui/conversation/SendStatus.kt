package app.clearsms.ui.conversation

import android.provider.Telephony

/**
 * Send state of an outgoing SMS as shown on its bubble.
 *
 * SMS sending is asynchronous: [app.clearsms.sms.SmsSender] hands the message
 * to the radio and the real result arrives later through the sent-report
 * PendingIntent handled by [app.clearsms.receiver.SmsSentReceiver], which
 * records a failure by flipping the system provider row to
 * `MESSAGE_TYPE_FAILED`. Success is never positively recorded, so the states
 * mean exactly:
 *
 * - [SENDING] — handed to [android.telephony.SmsManager]; no radio result has
 *   been observed yet.
 * - [SENT] — no failure was recorded against the persisted provider row
 *   within [SendOutcome.RESULT_WINDOW_MS] of dispatch (the radio reports
 *   failures within a couple of seconds, so a quiet window means the carrier
 *   accepted the message). This is honesty-by-absence, not proof of delivery.
 * - [FAILED] — the send call itself threw, or [app.clearsms.receiver.SmsSentReceiver]
 *   marked the provider row `MESSAGE_TYPE_FAILED` after a non-OK radio result.
 */
enum class SendStatus { SENDING, SENT, FAILED }

/**
 * Pure state machine resolving a send outcome from the persisted provider
 * row's `type` column and the time elapsed since dispatch.
 */
object SendOutcome {
    /**
     * How long a dispatch is watched for a radio failure report before the
     * send is called good. Sent reports normally arrive well under 2 s; the
     * window is generous without stalling the confirmation unreasonably.
     */
    const val RESULT_WINDOW_MS = 4_000L

    /** Interval between provider polls while a send is unresolved. */
    const val POLL_INTERVAL_MS = 400L

    /**
     * @param providerType the `type` column of the outgoing message's system
     *   provider row, or null when no row is observable (for example the app
     *   was not the default SMS app, so the row was never written).
     * @param elapsedMs milliseconds since the message was handed to the radio.
     */
    fun resolve(
        providerType: Int?,
        elapsedMs: Long,
    ): SendStatus =
        when {
            providerType == Telephony.Sms.MESSAGE_TYPE_FAILED -> SendStatus.FAILED
            elapsedMs >= RESULT_WINDOW_MS -> SendStatus.SENT
            else -> SendStatus.SENDING
        }
}
