package app.clearsms.ui.conversation

import app.clearsms.data.db.DeliveryStatus
import app.clearsms.ui.conversation.MessageDetails.Transport

/**
 * The one rule for what an outgoing row may claim while its send is
 * unresolved, per transport. Pure - no clock, no database - so the
 * invariant is testable on its own and every caller (the watcher, the
 * bubble) reads the same truth.
 *
 * SMS: the radio reports a failure within a couple of seconds, so a quiet
 * result window is honest evidence the SMSC accepted the message -
 * SENDING may be closed as SENT by the passage of time
 * (honesty-by-absence, see [SentMessageWatcher]).
 *
 * MMS: there is exactly ONE result, the platform's sent PendingIntent
 * ([app.clearsms.receiver.MmsSentReceiver]), and the platform may hold the
 * message for minutes before reporting - a Samsung SM-G990E was observed
 * taking 149 s to give up with NO_MMS_NETWORK. Time therefore proves
 * nothing about an MMS: the row stays SENDING until that result lands, and
 * the only writer allowed to promote it to SENT is the receiver's OK.
 */
object OutgoingSendPolicy {
    /**
     * The status an outgoing row should carry once the silent result window
     * has elapsed, given what is persisted and whether a real report has
     * been recorded against it.
     *
     * A recorded result is final and returned unchanged for either
     * transport. Without one, only an SMS in [DeliveryStatus.SENDING] is
     * promoted to [DeliveryStatus.SENT]; an MMS in flight stays
     * [DeliveryStatus.SENDING], whatever the clock says.
     */
    fun afterSilentWindow(
        transport: Transport,
        persisted: DeliveryStatus,
        resultRecorded: Boolean,
    ): DeliveryStatus {
        if (resultRecorded) return persisted
        if (persisted != DeliveryStatus.SENDING) return persisted
        return when (transport) {
            Transport.SMS -> DeliveryStatus.SENT
            Transport.MMS -> DeliveryStatus.SENDING
        }
    }
}
