package app.clearsms.ui.conversation

import app.clearsms.data.db.DeliveryStatus
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.db.MmsStatus
import app.clearsms.domain.model.Category
import app.clearsms.ui.conversation.MessageDetails.Transport
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * The bubble tick follows the persisted [DeliveryStatus] exactly (GitHub
 * #44): one tick for SENT, two for a real DELIVERED, and NOTHING for every
 * state that has not left the phone or has failed - a failed message must
 * never look like a tick state, and an MMS must never look delivered. All
 * fixtures are synthetic.
 */
class DeliveryTickTest {
    private fun entity(
        outgoing: Boolean,
        status: DeliveryStatus? = null,
        attachmentKinds: String? = null,
        mmsStatus: MmsStatus? = null,
    ) = MessageEntity(
        id = 3,
        threadId = 1,
        sender = "5550100",
        normalizedSender = "5550100",
        body = "synthetic",
        timestamp = 1_700_000_000_000,
        category = Category.PERSONAL,
        isOutgoing = outgoing,
        deliveryStatus = if (outgoing) status else null,
        attachmentKinds = attachmentKinds,
        mmsStatus = mmsStatus,
    )

    @Test
    fun `sent SMS - single tick`() {
        assertThat(DeliveryTicks.tickFor(entity(outgoing = true, status = DeliveryStatus.SENT)))
            .isEqualTo(DeliveryTick.SINGLE)
    }

    @Test
    fun `delivered SMS - double tick, with or without a recorded acknowledgement time`() {
        // The tick is about the STATUS (a real report arrived); whether the
        // report's arrival time was recorded only affects the details row.
        assertThat(DeliveryTicks.tickFor(entity(outgoing = true, status = DeliveryStatus.DELIVERED)))
            .isEqualTo(DeliveryTick.DOUBLE)
        assertThat(
            DeliveryTicks.tickFor(
                entity(outgoing = true, status = DeliveryStatus.DELIVERED).copy(deliveredAt = 1_700_000_005_000),
            ),
        ).isEqualTo(DeliveryTick.DOUBLE)
    }

    @Test
    fun `failed - no tick, so it can never be mistaken for sent or delivered`() {
        assertThat(DeliveryTicks.tickFor(entity(outgoing = true, status = DeliveryStatus.FAILED)))
            .isEqualTo(DeliveryTick.NONE)
        // Even a stale acknowledgement time on a demoted row changes nothing.
        assertThat(
            DeliveryTicks.tickFor(
                entity(outgoing = true, status = DeliveryStatus.FAILED).copy(deliveredAt = 1_700_000_005_000),
            ),
        ).isEqualTo(DeliveryTick.NONE)
    }

    @Test
    fun `sending - no tick until the radio's sent report`() {
        assertThat(DeliveryTicks.tickFor(entity(outgoing = true, status = DeliveryStatus.SENDING)))
            .isEqualTo(DeliveryTick.NONE)
    }

    @Test
    fun `scheduled (incl the delayed-send pending bubble) - no tick, nothing has left the phone`() {
        assertThat(DeliveryTicks.tickFor(entity(outgoing = true, status = DeliveryStatus.SCHEDULED)))
            .isEqualTo(DeliveryTick.NONE)
    }

    @Test
    fun `outgoing with no recorded status - no tick rather than a guessed one`() {
        assertThat(DeliveryTicks.tickFor(entity(outgoing = true, status = null))).isEqualTo(DeliveryTick.NONE)
    }

    @Test
    fun `incoming - never a tick, whatever the row says`() {
        assertThat(DeliveryTicks.tickFor(entity(outgoing = false))).isEqualTo(DeliveryTick.NONE)
        assertThat(DeliveryTicks.tickFor(entity(outgoing = false, mmsStatus = MmsStatus.DOWNLOADED)))
            .isEqualTo(DeliveryTick.NONE)
        // Defensive: an incoming row that somehow carries a status still gets none.
        assertThat(DeliveryTicks.tickFor(outgoing = false, status = DeliveryStatus.DELIVERED, transport = Transport.SMS))
            .isEqualTo(DeliveryTick.NONE)
    }

    @Test
    fun `outgoing MMS - single tick when sent, and CAPPED at single even if marked delivered`() {
        // MMS delivery reports are not supported: an MMS can honestly say
        // "left the phone", never "delivered".
        assertThat(DeliveryTicks.tickFor(entity(outgoing = true, status = DeliveryStatus.SENT, attachmentKinds = "IMAGE")))
            .isEqualTo(DeliveryTick.SINGLE)
        assertThat(
            DeliveryTicks.tickFor(entity(outgoing = true, status = DeliveryStatus.DELIVERED, attachmentKinds = "IMAGE")),
        ).isEqualTo(DeliveryTick.SINGLE)
        assertThat(DeliveryTicks.tickFor(entity(outgoing = true, status = DeliveryStatus.FAILED, attachmentKinds = "FILE")))
            .isEqualTo(DeliveryTick.NONE)
    }

    @Test
    fun `conversation item view - uses the item's direction and status, SMS when no backing row`() {
        val sent = entity(outgoing = true, status = DeliveryStatus.SENT)
        assertThat(DeliveryTicks.tickFor(sent.toConversationItem(Json)))
            .isEqualTo(DeliveryTick.SINGLE)
        val delivered = entity(outgoing = true, status = DeliveryStatus.DELIVERED)
        assertThat(DeliveryTicks.tickFor(delivered.toConversationItem(Json)))
            .isEqualTo(DeliveryTick.DOUBLE)
        val mmsDelivered = entity(outgoing = true, status = DeliveryStatus.DELIVERED, attachmentKinds = "IMAGE")
        assertThat(DeliveryTicks.tickFor(mmsDelivered.toConversationItem(Json)))
            .isEqualTo(DeliveryTick.SINGLE)
        assertThat(
            DeliveryTicks.tickFor(
                ConversationItem(id = 1, body = "", timestamp = 0, outgoing = true, deliveryStatus = DeliveryStatus.DELIVERED),
            ),
        ).isEqualTo(DeliveryTick.DOUBLE)
        assertThat(
            DeliveryTicks.tickFor(ConversationItem(id = 2, body = "", timestamp = 0, outgoing = false)),
        ).isEqualTo(DeliveryTick.NONE)
    }
}
