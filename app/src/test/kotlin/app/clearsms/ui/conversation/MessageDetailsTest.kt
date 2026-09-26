package app.clearsms.ui.conversation

import app.clearsms.data.db.DeliveryStatus
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.db.MmsStatus
import app.clearsms.domain.model.Category
import app.clearsms.mms.SendFailureReason
import app.clearsms.ui.conversation.MessageDetails.DeliveryKnowledge
import app.clearsms.ui.conversation.MessageDetails.Row
import app.clearsms.ui.conversation.MessageDetails.TimeKind
import app.clearsms.ui.conversation.MessageDetails.Transport
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The "More details" rows come straight from persisted state, and delivery
 * is reported HONESTLY: only a real carrier report reads as delivered
 * (never with a fabricated time - none is recorded), a sent-without-report
 * SMS is Unknown, and outgoing MMS is always Unknown because this app does
 * not support MMS delivery reports. All fixtures are synthetic.
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
    )

    private fun rows(
        message: MessageEntity,
        name: String? = null,
        sim: String? = null,
    ) = MessageDetails.rowsFor(message, resolvedName = name, simLabel = sim)

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
        assertThat(rows.filterIsInstance<Row.SentTimeUnknown>()).isEmpty()
        // The outgoing "Sent" kind is never used for an incoming message.
        assertThat(rows.filterIsInstance<Row.Timestamp>().map { it.kind }).doesNotContain(TimeKind.SENT)
    }

    @Test
    fun `incoming SMS without a network sent time - says so instead of inventing or duplicating one`() {
        val rows = rows(entity(outgoing = false, dateSent = null))

        assertThat(rows[2]).isEqualTo(Row.SentTimeUnknown)
        assertThat(rows[3]).isEqualTo(Row.Timestamp(TimeKind.RECEIVED, 1_700_000_000_000))
        // Exactly ONE timestamp row: the received time is never shown twice
        // under a "Sent" label.
        assertThat(rows.filterIsInstance<Row.Timestamp>()).hasSize(1)
    }

    @Test
    fun `outgoing SMS - exactly one time row, Sent, and no received time the phone never learns`() {
        val rows = rows(entity(outgoing = true, status = DeliveryStatus.SENT))

        assertThat(rows.filterIsInstance<Row.Timestamp>())
            .containsExactly(Row.Timestamp(TimeKind.SENT, 1_700_000_000_000))
        assertThat(rows.filterIsInstance<Row.SentTimeUnknown>()).isEmpty()
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

    @Test
    fun `sim tag surfaces as a row only when known`() {
        assertThat(rows(entity(outgoing = true), sim = "SIM 2")).contains(Row.Sim("SIM 2"))
        assertThat(rows(entity(outgoing = true)).filterIsInstance<Row.Sim>()).isEmpty()
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
