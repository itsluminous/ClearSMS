package app.clearsms.ui.conversation

import app.clearsms.data.db.DeliveryStatus
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.db.MmsStatus
import app.clearsms.domain.model.Category
import app.clearsms.mms.DataSimHint
import app.clearsms.mms.SendFailureReason
import app.clearsms.sms.SimInfo
import app.clearsms.ui.conversation.MessageDetails.DeliveryKnowledge
import app.clearsms.ui.conversation.MessageDetails.Row
import app.clearsms.ui.conversation.MessageDetails.TimeKind
import app.clearsms.ui.conversation.MessageDetails.Transport
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The "More details" rows come straight from persisted state, and delivery
 * is reported HONESTLY and briefly: only a real carrier report reads as
 * delivered - the time when the app recorded processing that report, else a
 * plain "Yes" (never a fabricated time) - a sent-without-report SMS is
 * Unknown, and outgoing MMS is always Unknown because this app does not
 * support MMS delivery reports. An incoming message with no network sent
 * time has NO sent row at all. All fixtures are synthetic.
 */
class MessageDetailsTest {
    private fun entity(
        outgoing: Boolean,
        status: DeliveryStatus? = null,
        mmsStatus: MmsStatus? = null,
        attachmentKinds: String? = null,
        sendFailureReason: String? = null,
        deletedAt: Long? = null,
        dateSent: Long? = null,
        subscriptionId: Int? = null,
    ) = MessageEntity(
        id = 7,
        threadId = 1,
        sender = "5550100",
        normalizedSender = "5550100",
        body = "synthetic body",
        timestamp = 1_700_000_000_000,
        category = Category.PERSONAL,
        isOutgoing = outgoing,
        deliveryStatus = if (outgoing) status else null,
        mmsStatus = mmsStatus,
        attachmentKinds = attachmentKinds,
        sendFailureReason = sendFailureReason,
        deletedAt = deletedAt,
        dateSent = dateSent,
        subscriptionId = subscriptionId,
    )

    // Synthetic dual-SIM device: two subscriptions, two carriers.
    private val dualSims =
        listOf(
            SimInfo(subscriptionId = 10, slotIndex = 0, displayName = "Carrier A"),
            SimInfo(subscriptionId = 20, slotIndex = 1, displayName = "Carrier B"),
        )

    private fun rows(
        message: MessageEntity,
        name: String? = null,
        sims: List<SimInfo> = emptyList(),
    ) = MessageDetails.rowsFor(message, resolvedName = name, activeSims = sims)

    private fun simRow(
        message: MessageEntity,
        sims: List<SimInfo>,
    ): Row.Sim? = rows(message, sims = sims).filterIsInstance<Row.Sim>().singleOrNull()

    @Test
    fun `incoming SMS - type, From with resolved name, received time, no delivery or error rows`() {
        val rows = rows(entity(outgoing = false), name = "Test Sender")

        assertThat(rows[0]).isEqualTo(Row.Type(Transport.SMS))
        assertThat(rows[1]).isEqualTo(
            Row.Counterparty(outgoing = false, address = "5550100", resolvedName = "Test Sender"),
        )
        assertThat(rows).contains(Row.Timestamp(TimeKind.RECEIVED, 1_700_000_000_000))
        assertThat(rows.filterIsInstance<Row.Delivered>()).isEmpty()
        assertThat(rows.filterIsInstance<Row.Error>()).isEmpty()
    }

    @Test
    fun `incoming SMS with a network sent time - sent row (network-labelled) then received row, distinct instants`() {
        // Sent 4.5 s before it was received: both rows carry their own
        // instant (rendered with seconds), labelled so neither is mistaken
        // for the other.
        val rows = rows(entity(outgoing = false, dateSent = 1_699_999_995_500))

        assertThat(rows[2]).isEqualTo(Row.Timestamp(TimeKind.SENT_BY_NETWORK, 1_699_999_995_500))
        assertThat(rows[3]).isEqualTo(Row.Timestamp(TimeKind.RECEIVED, 1_700_000_000_000))
        // The outgoing "Sent" kind is never used for an incoming message.
        assertThat(rows.filterIsInstance<Row.Timestamp>().map { it.kind }).doesNotContain(TimeKind.SENT)
    }

    @Test
    fun `incoming SMS without a network sent time - NO sent row at all, nothing invented or explained`() {
        val rows = rows(entity(outgoing = false, dateSent = null))

        // Straight from the From row to the Received row: the Sent row is
        // absent, not present with empty or "not reported" text.
        assertThat(rows[2]).isEqualTo(Row.Timestamp(TimeKind.RECEIVED, 1_700_000_000_000))
        assertThat(rows.filterIsInstance<Row.Timestamp>().map { it.kind })
            .containsExactly(TimeKind.RECEIVED)
        // Exactly ONE timestamp row: the received time is never shown twice
        // under a "Sent" label.
        assertThat(rows.filterIsInstance<Row.Timestamp>()).hasSize(1)
        assertThat(rows).hasSize(3)
    }

    @Test
    fun `outgoing SMS - exactly one time row, Sent, and no received time the phone never learns`() {
        val rows = rows(entity(outgoing = true, status = DeliveryStatus.SENT))

        assertThat(rows.filterIsInstance<Row.Timestamp>())
            .containsExactly(Row.Timestamp(TimeKind.SENT, 1_700_000_000_000))
    }

    @Test
    fun `outgoing SMS with a real delivery report - delivered confirmed, sent time, To row`() {
        val rows = rows(entity(outgoing = true, status = DeliveryStatus.DELIVERED))

        assertThat(rows).contains(Row.Delivered(DeliveryKnowledge.CONFIRMED))
        assertThat(rows).contains(Row.Timestamp(TimeKind.SENT, 1_700_000_000_000))
        assertThat(rows[1]).isEqualTo(
            Row.Counterparty(outgoing = true, address = "5550100", resolvedName = null),
        )
    }

    @Test
    fun `delivered SMS with a recorded acknowledgement - the report's processing time is the delivery time`() {
        // GitHub #44: the phone handled the carrier's delivery report at
        // this instant, so that IS shown - as the acknowledgement time.
        val rows =
            rows(
                entity(outgoing = true, status = DeliveryStatus.DELIVERED)
                    .copy(deliveredAt = 1_700_000_012_345),
            )

        val delivered = rows.filterIsInstance<Row.Delivered>().single()
        assertThat(delivered).isEqualTo(Row.Delivered(DeliveryKnowledge.CONFIRMED, acknowledgedAtMs = 1_700_000_012_345))
        assertThat(delivered.acknowledgedAtMs).isEqualTo(1_700_000_012_345)
        // Still exactly one plain time row (Sent): the acknowledgement rides
        // on the Delivered row, it is not a second "Received"-style row.
        assertThat(rows.filterIsInstance<Row.Timestamp>())
            .containsExactly(Row.Timestamp(TimeKind.SENT, 1_700_000_000_000))
    }

    @Test
    fun `delivered SMS whose report arrival was never recorded - confirmed WITHOUT a time, so the row is a bare Yes`() {
        // Pre-column rows and provider-imported delivered rows: a report
        // exists, but when it arrived is unknown - so no time, and the
        // dialog renders CONFIRMED-without-time as exactly "Yes".
        val delivered =
            rows(entity(outgoing = true, status = DeliveryStatus.DELIVERED))
                .filterIsInstance<Row.Delivered>()
                .single()

        assertThat(delivered.knowledge).isEqualTo(DeliveryKnowledge.CONFIRMED)
        assertThat(delivered.acknowledgedAtMs).isNull()
    }

    @Test
    fun `sent with NO report - unknown and no time, even if a stale acknowledgement is on the row`() {
        val delivered =
            rows(
                entity(outgoing = true, status = DeliveryStatus.SENT)
                    .copy(deliveredAt = 1_700_000_012_345),
            ).filterIsInstance<Row.Delivered>().single()

        assertThat(delivered).isEqualTo(Row.Delivered(DeliveryKnowledge.UNKNOWN_NO_REPORT))
        assertThat(delivered.acknowledgedAtMs).isNull()
    }

    @Test
    fun `failed with a stale acknowledgement - error row only, no delivery row at all`() {
        val rows =
            rows(
                entity(outgoing = true, status = DeliveryStatus.FAILED)
                    .copy(deliveredAt = 1_700_000_012_345),
            )

        assertThat(rows).contains(Row.Error(null))
        assertThat(rows.filterIsInstance<Row.Delivered>()).isEmpty()
    }

    @Test
    fun `outgoing MMS marked delivered - still honestly unsupported, never CONFIRMED, no acknowledgement time`() {
        // MMS delivery reports are not supported, so even a DELIVERED-marked
        // MMS row (which the send path never produces) claims nothing: it
        // must never reach the "Yes" wording.
        val delivered =
            rows(
                entity(outgoing = true, status = DeliveryStatus.DELIVERED, attachmentKinds = "IMAGE")
                    .copy(deliveredAt = 1_700_000_012_345),
            ).filterIsInstance<Row.Delivered>().single()

        assertThat(delivered).isEqualTo(Row.Delivered(DeliveryKnowledge.UNSUPPORTED_MMS))
        assertThat(delivered.knowledge).isNotEqualTo(DeliveryKnowledge.CONFIRMED)
        assertThat(delivered.acknowledgedAtMs).isNull()
    }

    @Test
    fun `outgoing SMS sent but no report - delivery is UNKNOWN, never claimed delivered`() {
        val rows = rows(entity(outgoing = true, status = DeliveryStatus.SENT))

        assertThat(rows).contains(Row.Delivered(DeliveryKnowledge.UNKNOWN_NO_REPORT))
        assertThat(rows).doesNotContain(Row.Delivered(DeliveryKnowledge.CONFIRMED))
    }

    @Test
    fun `failed outgoing - error row with the recorded reason, no delivery row`() {
        val failed =
            entity(
                outgoing = true,
                status = DeliveryStatus.FAILED,
                sendFailureReason = SendFailureReason.NO_MMS_NETWORK.name,
            )

        val rows = rows(failed)

        assertThat(rows).contains(Row.Error(SendFailureReason.NO_MMS_NETWORK))
        assertThat(rows.filterIsInstance<Row.Delivered>()).isEmpty()
    }

    @Test
    fun `failed outgoing MMS off the data SIM - the error row carries the already-judged data-SIM hint`() {
        val failed =
            entity(
                outgoing = true,
                status = DeliveryStatus.FAILED,
                attachmentKinds = "IMAGE",
                sendFailureReason = SendFailureReason.NO_MMS_NETWORK.name,
            )
        val hint = DataSimHint(sendingSlot = 2, dataSlot = 1)

        val rows = MessageDetails.rowsFor(failed, resolvedName = null, activeSims = dualSims, dataSimHint = hint)

        assertThat(rows).contains(Row.Error(SendFailureReason.NO_MMS_NETWORK, hint))
        // The hint rides on the error row only: a non-failed row never gets one.
        val sent =
            MessageDetails.rowsFor(
                entity(outgoing = true, status = DeliveryStatus.SENT),
                null,
                dualSims,
                dataSimHint = hint,
            )
        assertThat(sent.filterIsInstance<Row.Error>()).isEmpty()
    }

    @Test
    fun `failed outgoing without a recorded reason - error row with null reason`() {
        val rows = rows(entity(outgoing = true, status = DeliveryStatus.FAILED))

        assertThat(rows).contains(Row.Error(null))
    }

    @Test
    fun `outgoing MMS - type MMS and delivery honestly unsupported`() {
        val mms = entity(outgoing = true, status = DeliveryStatus.SENT, attachmentKinds = "IMAGE")

        val rows = rows(mms)

        assertThat(rows[0]).isEqualTo(Row.Type(Transport.MMS))
        assertThat(rows).contains(Row.Delivered(DeliveryKnowledge.UNSUPPORTED_MMS))
    }

    @Test
    fun `incoming MMS is typed MMS from its mms status`() {
        val rows = rows(entity(outgoing = false, mmsStatus = MmsStatus.DOWNLOADED))

        assertThat(rows[0]).isEqualTo(Row.Type(Transport.MMS))
    }

    @Test
    fun `sending or scheduled - no delivery claim, scheduled shows its future time kind`() {
        assertThat(rows(entity(outgoing = true, status = DeliveryStatus.SENDING)).filterIsInstance<Row.Delivered>())
            .isEmpty()
        val scheduled = rows(entity(outgoing = true, status = DeliveryStatus.SCHEDULED))
        assertThat(scheduled.filterIsInstance<Row.Delivered>()).isEmpty()
        assertThat(scheduled).contains(Row.Timestamp(TimeKind.SCHEDULED, 1_700_000_000_000))
    }

    // --- SIM row: slot first, carrier second, and the honest unknowns -------

    @Test
    fun `sim row names slot AND carrier when the subscription is on the device`() {
        val row = simRow(entity(outgoing = true, subscriptionId = 20), dualSims)

        assertThat(row).isEqualTo(Row.Sim(slot = 2, operatorName = "Carrier B"))
        assertThat(row!!.label).isEqualTo("SIM 2 - Carrier B")
    }

    @Test
    fun `sim row degrades to the bare slot when the name is blank`() {
        val nameless =
            listOf(
                SimInfo(subscriptionId = 10, slotIndex = 0, displayName = "Carrier A"),
                SimInfo(subscriptionId = 20, slotIndex = 1, displayName = "  "),
            )

        val row = simRow(entity(outgoing = false, subscriptionId = 20), nameless)

        assertThat(row!!.label).isEqualTo("SIM 2")
        // Never a dangling separator.
        assertThat(row.label).doesNotContain("-")
    }

    @Test
    fun `two SIMs on the SAME carrier stay distinguishable - the slot leads`() {
        // GitHub #7's setup: the name alone would be identical on both rows.
        val sameCarrier =
            listOf(
                SimInfo(subscriptionId = 10, slotIndex = 0, displayName = "Carrier A"),
                SimInfo(subscriptionId = 20, slotIndex = 1, displayName = "Carrier A"),
            )

        val first = simRow(entity(outgoing = false, subscriptionId = 10), sameCarrier)!!
        val second = simRow(entity(outgoing = false, subscriptionId = 20), sameCarrier)!!

        assertThat(first.label).isEqualTo("SIM 1 - Carrier A")
        assertThat(second.label).isEqualTo("SIM 2 - Carrier A")
        assertThat(first.label).isNotEqualTo(second.label)
        assertThat(first.label).startsWith("SIM 1")
        assertThat(second.label).startsWith("SIM 2")
    }

    @Test
    fun `a subscription no longer on the device gets NO row - never a stale or swapped carrier`() {
        // The message came in on subscription 30, since removed; slot 2 now
        // holds a different SIM (20, Carrier B). Naming slot 2's current
        // occupant would attribute the message to the wrong carrier, and
        // the removed SIM's own name is unknowable - so nothing is said.
        val row = simRow(entity(outgoing = false, subscriptionId = 30), dualSims)

        assertThat(row).isNull()
        val labels = rows(entity(outgoing = false, subscriptionId = 30), sims = dualSims).filterIsInstance<Row.Sim>()
        assertThat(labels).isEmpty()
    }

    @Test
    fun `a null subscription id (older imported rows) gets no row`() {
        assertThat(simRow(entity(outgoing = true, subscriptionId = null), dualSims)).isNull()
        // Even with an active SIM list, null never "defaults" to any slot.
        assertThat(rows(entity(outgoing = true), sims = dualSims).filterIsInstance<Row.Sim>()).isEmpty()
    }

    @Test
    fun `single-SIM device still names its one SIM with slot and carrier`() {
        // The fact is known and the dialog is the verbose place for it; the
        // bubble tag (hidden on single-SIM phones) is a separate decision.
        val single = listOf(SimInfo(subscriptionId = 10, slotIndex = 0, displayName = "Carrier A"))

        val row = simRow(entity(outgoing = true, subscriptionId = 10), single)

        assertThat(row!!.label).isEqualTo("SIM 1 - Carrier A")
        // ...but a message from some OTHER, departed subscription says nothing.
        assertThat(simRow(entity(outgoing = true, subscriptionId = 20), single)).isNull()
    }

    @Test
    fun `no SIMs known at all - no row, whatever the message recorded`() {
        // Permission-less or telephony-less: the list is empty, and no
        // subscription id can be vouched for.
        assertThat(simRow(entity(outgoing = true, subscriptionId = 10), emptyList())).isNull()
    }

    @Test
    fun `sim row sits after the delivery rows and before the recycle-bin row`() {
        val rows =
            rows(
                entity(outgoing = true, status = DeliveryStatus.SENT, subscriptionId = 10, deletedAt = 1_700_000_100_000),
                sims = dualSims,
            )

        val simIndex = rows.indexOfFirst { it is Row.Sim }
        assertThat(simIndex).isGreaterThan(rows.indexOfFirst { it is Row.Delivered })
        assertThat(rows.last()).isEqualTo(Row.InRecycleBin)
        assertThat(simIndex).isEqualTo(rows.lastIndex - 1)
    }

    @Test
    fun `binned message states its recycle-bin status instead of lying`() {
        assertThat(rows(entity(outgoing = false, deletedAt = 1_700_000_100_000)))
            .contains(Row.InRecycleBin)
        assertThat(rows(entity(outgoing = false)).filterIsInstance<Row.InRecycleBin>()).isEmpty()
    }

    @Test
    fun `a resolved name equal to the raw address is not repeated`() {
        val row =
            rows(entity(outgoing = false), name = "5550100")
                .filterIsInstance<Row.Counterparty>()
                .single()

        assertThat(row.resolvedName).isNull()
        assertThat(row.address).isEqualTo("5550100")
    }
}
