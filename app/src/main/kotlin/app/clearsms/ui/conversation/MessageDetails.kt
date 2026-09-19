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
 * a delivered time is NEVER invented. The app records only that a real
 * carrier delivery report arrived (not when), so [Row.Delivered] carries a
 * [DeliveryKnowledge] instead of a fabricated timestamp - CONFIRMED when a
 * report exists, UNKNOWN_NO_REPORT for a sent SMS without one, and
 * UNSUPPORTED_MMS for outgoing MMS (this app does not support MMS delivery
 * reports at all).
 */
object MessageDetails {
    /** What carried the message. */
    enum class Transport { SMS, MMS }

    /** Which timestamp the message's single time row shows. */
    enum class TimeKind { RECEIVED, SENT, SCHEDULED }

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

        /** Exact date+time; [kind] picks the Received / Sent / Scheduled label. */
        data class Timestamp(
            val kind: TimeKind,
            val timestampMs: Long,
        ) : Row

        /** Delivery knowledge for a message that left the phone (SENT/DELIVERED). */
        data class Delivered(
            val knowledge: DeliveryKnowledge,
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
            val timeKind =
                when {
                    !message.isOutgoing -> TimeKind.RECEIVED
                    message.deliveryStatus == DeliveryStatus.SCHEDULED -> TimeKind.SCHEDULED
                    else -> TimeKind.SENT
                }
            add(Row.Timestamp(timeKind, message.timestamp))
            if (message.isOutgoing) {
                when (message.deliveryStatus) {
                    DeliveryStatus.DELIVERED -> add(Row.Delivered(DeliveryKnowledge.CONFIRMED))
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
