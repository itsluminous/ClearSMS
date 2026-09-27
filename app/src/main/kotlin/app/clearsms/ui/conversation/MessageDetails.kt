package app.clearsms.ui.conversation

import app.clearsms.data.db.DeliveryStatus
import app.clearsms.data.db.MessageEntity
import app.clearsms.mms.SendFailureReason

/**
 * Pure mapping from a message row (plus its already-resolved display name
 * and SIM tag) to the rows the "More details" dialog shows. Everything comes
 * from the persisted state the app already maintains - no parallel source of
 * truth: [DeliveryStatus] for the send lifecycle, [MessageEntity.sendFailureReason]
 * for failures, [MessageEntity.subscriptionId] (via the precomputed SIM tag)
 * for provenance, and the screen's contact → sender-directory → raw-address
 * resolution for the name.
 *
 * Honesty rules, matching the bubble status line ([deliveryStatusLabelRes]):
 * no time is EVER invented. An incoming message shows the sender's network
 * timestamp ([MessageEntity.dateSent]) only when one was recorded, beside
 * the received time, both with seconds. For an outgoing SMS the app records
 * WHEN it processed the carrier delivery report that completed delivery
 * ([MessageEntity.deliveredAt]) - the acknowledgement time, a close proxy
 * for the delivery time but not the carrier's own timestamp - so
 * [Row.Delivered] carries a [DeliveryKnowledge] plus that instant when one
 * was recorded: CONFIRMED with the acknowledgement time for a report this
 * build handled, CONFIRMED without one for a report that predates the
 * column or came in through the provider import (a report exists, its
 * arrival was never recorded), UNKNOWN_NO_REPORT for a sent SMS without any
 * report (never a fabricated time), and UNSUPPORTED_MMS for outgoing MMS
 * (this app does not support MMS delivery reports at all).
 */
object MessageDetails {
    /** What carried the message. */
    enum class Transport { SMS, MMS }

    /**
     * Which instant a time row shows. Every row is labelled so the two
     * clocks of one incoming message are never confused: [RECEIVED] is when
     * this device got it, [SENT_BY_NETWORK] is the sender's network (SMSC)
     * timestamp from the PDU, [SENT] is when this device dispatched an
     * outgoing message, [SCHEDULED] its future fire time.
     */
    enum class TimeKind { RECEIVED, SENT_BY_NETWORK, SENT, SCHEDULED }

    /** What the app truthfully knows about delivery of an outgoing message. */
    enum class DeliveryKnowledge {
        /** A real carrier delivery report arrived (every part, for multipart). */
        CONFIRMED,

        /** Sent, but no delivery report came back - honestly unknown. */
        UNKNOWN_NO_REPORT,

        /** Outgoing MMS: delivery reports are not supported, so always unknown. */
        UNSUPPORTED_MMS,
    }

    /** One labeled row of the details dialog, in display order. */
    sealed interface Row {
        data class Type(
            val transport: Transport,
        ) : Row

        /**
         * The other party: "To" for outgoing, "From" for incoming.
         * [resolvedName] is the contact / sender-directory name when one
         * resolved AND differs from the raw [address]; the dialog shows the
         * name first with the address beneath it, or just the address.
         */
        data class Counterparty(
            val outgoing: Boolean,
            val address: String,
            val resolvedName: String?,
        ) : Row

        /**
         * Exact date+time WITH seconds; [kind] picks the Received / Sent /
         * Scheduled label.
         */
        data class Timestamp(
            val kind: TimeKind,
            val timestampMs: Long,
        ) : Row

        /**
         * An incoming message whose sender timestamp the network never
         * reported (provider `DATE_SENT` 0/absent): the row says so instead
         * of showing the received time twice or inventing a value.
         */
        data object SentTimeUnknown : Row

        /**
         * Delivery knowledge for a message that left the phone (SENT/DELIVERED).
         * [acknowledgedAtMs] is set only with [DeliveryKnowledge.CONFIRMED],
         * and only when the app recorded when it processed the completing
         * delivery report; the dialog shows it as the delivery time, labelled
         * as the report's arrival on this phone. Null = no time is shown.
         */
        data class Delivered(
            val knowledge: DeliveryKnowledge,
            val acknowledgedAtMs: Long? = null,
        ) : Row

        /** The send FAILED; [reason] is the recorded cause, null when none was. */
        data class Error(
            val reason: SendFailureReason?,
        ) : Row

        /** "SIM 1"/"SIM 2" provenance, when known. */
        data class Sim(
            val label: String,
        ) : Row

        /** The row is soft-deleted - shown so a binned message never lies. */
        data object InRecycleBin : Row
    }

    /**
     * SMS vs MMS from the row's own denormalized markers: incoming MMS rows
     * carry an [MessageEntity.mmsStatus]; outgoing MMS rows carry
     * [MessageEntity.attachmentKinds] (SMS rows never set either).
     */
    fun transportOf(message: MessageEntity): Transport =
        if (message.mmsStatus != null || message.attachmentKinds != null) Transport.MMS else Transport.SMS

    /** The dialog's rows for [message], top to bottom. */
    fun rowsFor(
        message: MessageEntity,
        resolvedName: String?,
        simLabel: String?,
    ): List<Row> =
        buildList {
            val transport = transportOf(message)
            add(Row.Type(transport))
            add(
                Row.Counterparty(
                    outgoing = message.isOutgoing,
                    address = message.sender,
                    resolvedName = resolvedName?.takeIf { it.isNotBlank() && it != message.sender },
                ),
            )
            if (!message.isOutgoing) {
                // Sent first, then received - chronological, like AOSP. The
                // sent instant is the network's: shown only when recorded
                // (null = the SMSC stamped nothing, or an MMS), never
                // substituted with the received time.
                when (val sent = message.dateSent) {
                    null -> add(Row.SentTimeUnknown)
                    else -> add(Row.Timestamp(TimeKind.SENT_BY_NETWORK, sent))
                }
                add(Row.Timestamp(TimeKind.RECEIVED, message.timestamp))
            } else {
                val timeKind =
                    if (message.deliveryStatus == DeliveryStatus.SCHEDULED) TimeKind.SCHEDULED else TimeKind.SENT
                add(Row.Timestamp(timeKind, message.timestamp))
                // No received row for outgoing: the recipient's receipt time
                // is something this phone never learns.
            }
            if (message.isOutgoing) {
                when (message.deliveryStatus) {
                    // The acknowledgement time rides along only on a real
                    // DELIVERED SMS row. An MMS can never honestly be
                    // delivered here (no MMS delivery reports), so even a
                    // DELIVERED-marked MMS row reads as unsupported, with no
                    // time - never a delivery claim it cannot back.
                    DeliveryStatus.DELIVERED ->
                        add(
                            if (transport == Transport.MMS) {
                                Row.Delivered(DeliveryKnowledge.UNSUPPORTED_MMS)
                            } else {
                                Row.Delivered(DeliveryKnowledge.CONFIRMED, acknowledgedAtMs = message.deliveredAt)
                            },
                        )
                    DeliveryStatus.SENT ->
                        add(
                            Row.Delivered(
                                if (transport == Transport.MMS) {
                                    DeliveryKnowledge.UNSUPPORTED_MMS
                                } else {
                                    DeliveryKnowledge.UNKNOWN_NO_REPORT
                                },
                            ),
                        )
                    DeliveryStatus.FAILED ->
                        add(
                            Row.Error(
                                message.sendFailureReason?.let { name ->
                                    SendFailureReason.entries.firstOrNull { it.name == name }
                                },
                            ),
                        )
                    // Still SENDING or SCHEDULED: nothing has left the phone
                    // yet, so neither a delivery claim nor an error applies.
                    DeliveryStatus.SENDING, DeliveryStatus.SCHEDULED, null -> Unit
                }
            }
            simLabel?.let { add(Row.Sim(it)) }
            if (message.deletedAt != null) add(Row.InRecycleBin)
        }
}
