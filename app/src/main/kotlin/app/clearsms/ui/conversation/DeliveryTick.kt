package app.clearsms.ui.conversation

import app.clearsms.data.db.DeliveryStatus
import app.clearsms.data.db.MessageEntity

/**
 * The WhatsApp-style tick an outgoing bubble shows beside its time label.
 *
 * Pure mapping from persisted state - never from the tap or the passage of
 * time - following the existing [DeliveryStatus] model rather than
 * inventing states:
 *
 * - [SINGLE] for SENT: the radio (or, for MMS, the carrier's MMSC) accepted
 *   the message - it left the phone. Nothing more is claimed.
 * - [DOUBLE] for DELIVERED: a real carrier delivery report came back for
 *   every part. SMS only - MMS delivery reports are not supported by this
 *   app, so an MMS is capped at [SINGLE] whatever its row says, and can
 *   never look delivered.
 * - [NONE] for everything else. SENDING and FAILED keep their own explicit
 *   bubble line ("Sending…" / "Not sent" in the error colour), so an
 *   in-flight message never shows a tick it has not earned and a failed
 *   one can never be mistaken for a tick state. SCHEDULED (and the delayed
 *   send's pending bubble, which is a SCHEDULED row) shows its
 *   "Scheduled for …" line instead: the message has not left the phone, so
 *   a tick would be a false handover claim. Incoming messages carry no
 *   send lifecycle and get no tick.
 */
enum class DeliveryTick { NONE, SINGLE, DOUBLE }

object DeliveryTicks {
    fun tickFor(
        outgoing: Boolean,
        status: DeliveryStatus?,
        transport: MessageDetails.Transport,
    ): DeliveryTick {
        if (!outgoing) return DeliveryTick.NONE
        return when (status) {
            DeliveryStatus.SENT -> DeliveryTick.SINGLE
            DeliveryStatus.DELIVERED ->
                if (transport == MessageDetails.Transport.MMS) DeliveryTick.SINGLE else DeliveryTick.DOUBLE
            DeliveryStatus.SENDING, DeliveryStatus.FAILED, DeliveryStatus.SCHEDULED, null -> DeliveryTick.NONE
        }
    }

    fun tickFor(message: MessageEntity): DeliveryTick =
        tickFor(message.isOutgoing, message.deliveryStatus, MessageDetails.transportOf(message))

    /**
     * The bubble's view: direction and status come from the item (which
     * already nulls the status for incoming rows); SMS vs MMS from the
     * backing row, defaulting to SMS when the item carries none.
     */
    fun tickFor(item: ConversationItem): DeliveryTick =
        tickFor(
            outgoing = item.outgoing,
            status = item.deliveryStatus,
            transport = item.message?.let(MessageDetails::transportOf) ?: MessageDetails.Transport.SMS,
        )
}
