package app.clearsms.ui.conversation

import app.clearsms.data.db.DeliveryStatus
import app.clearsms.data.db.MessageEntity
import app.clearsms.mms.DataSimHint
import app.clearsms.mms.SendFailureReason
import app.clearsms.sms.SimInfo
import app.clearsms.sms.SimLabel

/**
 * Pure mapping from a message row (plus its already-resolved display name
 * and the device's current SIM list) to the rows the "More details" dialog shows. Everything comes
 * from the persisted state the app already maintains - no parallel source of
 * truth: [DeliveryStatus] for the send lifecycle, [MessageEntity.sendFailureReason]
 * for failures, [MessageEntity.subscriptionId] (resolved against the active
 * subscriptions, slot and carrier) for provenance, and the screen's contact →
 * sender-directory → raw-address resolution for the name.
 *
 * Honesty rules, matching the bubble status line ([deliveryStatusLabelRes]):
 * no time is EVER invented, and nothing is said about a time nobody knows.
 * An incoming message shows the sender's network timestamp
 * ([MessageEntity.dateSent]) only when one was recorded, beside the received
 * time, both with seconds; when the network stamped nothing there is simply
 * NO sent row (no "not reported" filler). For an outgoing SMS the app records
 * WHEN it processed the carrier delivery report that completed delivery
 * ([MessageEntity.deliveredAt]) - the acknowledgement time, a close proxy
 * for the delivery time - so [Row.Delivered] carries a [DeliveryKnowledge]
 * plus that instant when one was recorded: CONFIRMED with the
 * acknowledgement time for a report this build handled (the row shows the
 * time), CONFIRMED without one for a report that predates the column or came
 * in through the provider import (the row just says "Yes"), UNKNOWN_NO_REPORT
 * for a sent SMS without any report ("Unknown", never a fabricated time), and
 * UNSUPPORTED_MMS for outgoing MMS (this app does not support MMS delivery
 * reports at all, so an MMS never reads as delivered).
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
         * Delivery knowledge for a message that left the phone (SENT/DELIVERED).
         * [acknowledgedAtMs] is set only with [DeliveryKnowledge.CONFIRMED],
         * and only when the app recorded when it processed the completing
         * delivery report; the dialog shows it as the delivery time. Null =
         * no time is shown: a bare "Yes" for CONFIRMED, "Unknown" otherwise.
         */
        data class Delivered(
            val knowledge: DeliveryKnowledge,
            val acknowledgedAtMs: Long? = null,
        ) : Row

        /** The send FAILED; [reason] is the recorded cause, null when none was. */
        data class Error(
            val reason: SendFailureReason?,
            /**
             * Present only when the failed MMS went out on a SIM other than
             * the mobile-data SIM (see [app.clearsms.mms.DataSim.hintFor]);
             * the row then appends the data-SIM guidance to the explanation.
             */
            val dataSimHint: DataSimHint? = null,
        ) : Row

        /**
         * Which SIM carried the message - slot FIRST, carrier second
         * ("SIM 1 - Airtel"), through the shared [SimLabel] formatter the
         * compose bar's hint uses, so the two surfaces cannot drift. Present
         * ONLY when the message's [MessageEntity.subscriptionId] is a SIM
         * that is on the device NOW: that is the only source of a slot and
         * a name this phone can vouch for. A SIM removed or swapped since
         * the message arrived has no row (see [rowsFor]) rather than a
         * stale or - after a swap - WRONG carrier.
         */
        data class Sim(
            /** 1-based physical slot. */
            val slot: Int,
            /** Carrier / user nickname; blank degrades to the bare slot. */
            val operatorName: String,
        ) : Row {
            /** The row's text, from the ONE shared formatter. */
            val label: String get() = SimLabel.slotFirst(slot, operatorName)
        }

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

    /**
     * The dialog's rows for [message], top to bottom. [activeSims] is the
     * device's CURRENT subscription list (ViewModel -> UiState, like the
     * compose bar's indicator); [dataSimHint] is the already-judged
     * data-SIM addendum for a failed MMS (null = none).
     *
     * The SIM row and its honest unknowns:
     * - subscription active, name known -> "SIM 1 - Airtel" (two SIMs on
     *   the same carrier stay distinguishable by the slot);
     * - subscription active, name blank -> "SIM 1";
     * - single-SIM device -> still "SIM 1 - Airtel": the fact is known and
     *   the dialog is the verbose place for it (the bubble TAG is what is
     *   hidden on single-SIM phones, to keep every bubble from repeating it);
     * - [MessageEntity.subscriptionId] null (older imported rows, before
     *   the SIM-import fix) -> no row: nothing was recorded, nothing is said;
     * - subscription NOT on the device (SIM removed or swapped since) ->
     *   no row. The row stores only the subscription id; the slot and name
     *   lived on the SIM that is gone, and naming the slot's CURRENT
     *   occupant would attribute the message to a different carrier.
     *   Omitting beats guessing - the same rule the bubble tag follows.
     */
    fun rowsFor(
        message: MessageEntity,
        resolvedName: String?,
        activeSims: List<SimInfo>,
        dataSimHint: DataSimHint? = null,
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
                // substituted with the received time. With nothing recorded
                // there is no sent row at all - no "not reported" filler.
                message.dateSent?.let { add(Row.Timestamp(TimeKind.SENT_BY_NETWORK, it)) }
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
                    DeliveryStatus.DELIVERED -> {
                        add(
                            if (transport == Transport.MMS) {
                                Row.Delivered(DeliveryKnowledge.UNSUPPORTED_MMS)
                            } else {
                                Row.Delivered(DeliveryKnowledge.CONFIRMED, acknowledgedAtMs = message.deliveredAt)
                            },
                        )
                    }

                    DeliveryStatus.SENT -> {
                        add(
                            Row.Delivered(
                                if (transport == Transport.MMS) {
                                    DeliveryKnowledge.UNSUPPORTED_MMS
                                } else {
                                    DeliveryKnowledge.UNKNOWN_NO_REPORT
                                },
                            ),
                        )
                    }

                    DeliveryStatus.FAILED -> {
                        add(
                            Row.Error(SendFailureReason.fromName(message.sendFailureReason), dataSimHint),
                        )
                    }

                    // Still SENDING or SCHEDULED: nothing has left the phone
                    // yet, so neither a delivery claim nor an error applies.
                    DeliveryStatus.SENDING, DeliveryStatus.SCHEDULED, null -> {
                        Unit
                    }
                }
            }
            simRowFor(activeSims, message.subscriptionId)?.let { add(it) }
            if (message.deletedAt != null) add(Row.InRecycleBin)
        }

    /**
     * The SIM row for a stored subscription id, or null when this phone
     * cannot vouch for it: null id (never recorded) or a subscription no
     * longer among [activeSims] (removed / swapped). Matched by subscription
     * id - the platform mints a new one for every inserted SIM, so a match
     * is THAT SIM, never the slot's new occupant.
     */
    fun simRowFor(
        activeSims: List<SimInfo>,
        subscriptionId: Int?,
    ): Row.Sim? {
        if (subscriptionId == null) return null
        val sim = activeSims.firstOrNull { it.subscriptionId == subscriptionId } ?: return null
        return Row.Sim(slot = sim.slotIndex + 1, operatorName = sim.displayName)
    }
}
