package app.clearsms.ui.conversation

import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.model.Category
import app.clearsms.sms.SimInfo
import app.clearsms.sms.SimLabel
import app.clearsms.ui.common.RelativeTime
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.util.Locale

/**
 * The bubble SIM mark (GitHub #109) exists only when the device has two
 * SIMs AND the message's recorded subscription is one of them - and then
 * it is exactly the details dialog's SIM row, so the two surfaces cannot
 * disagree. All fixtures are synthetic.
 */
class BubbleSimIndicatorTest {
    private val english = RelativeTime.Strings("Today", "Yesterday", Locale.ENGLISH)
    private val simA = SimInfo(subscriptionId = 10, slotIndex = 0, displayName = "Carrier A")
    private val simB = SimInfo(subscriptionId = 20, slotIndex = 1, displayName = "Carrier B")
    private val dualSim = listOf(simA, simB)

    private fun entity(
        outgoing: Boolean,
        subscriptionId: Int?,
    ) = MessageEntity(
        id = 3,
        threadId = 1,
        sender = "5550100",
        normalizedSender = "5550100",
        body = "synthetic",
        timestamp = 1_700_000_000_000,
        category = Category.PERSONAL,
        isOutgoing = outgoing,
        subscriptionId = subscriptionId,
    )

    @Test
    fun `dual-SIM device, known subscription - the mark names that SIM, slot first`() {
        val mark = BubbleSimIndicators.forMessage(dualSim, subscriptionId = 20)
        assertThat(mark).isEqualTo(MessageDetails.Row.Sim(slot = 2, operatorName = "Carrier B"))
        assertThat(mark!!.label).isEqualTo(SimLabel.slotFirst(2, "Carrier B"))
        assertThat(BubbleSimIndicators.forMessage(dualSim, subscriptionId = 10)!!.slot).isEqualTo(1)
    }

    @Test
    fun `single-SIM device - nothing, even for a message from that very SIM`() {
        assertThat(BubbleSimIndicators.forMessage(listOf(simA), subscriptionId = 10)).isNull()
        // And with no SIMs readable at all (no permission, no telephony).
        assertThat(BubbleSimIndicators.forMessage(emptyList(), subscriptionId = 10)).isNull()
    }

    @Test
    fun `no recorded subscription - nothing, whatever the device has`() {
        assertThat(BubbleSimIndicators.forMessage(dualSim, subscriptionId = null)).isNull()
        assertThat(BubbleSimIndicators.forMessage(listOf(simA), subscriptionId = null)).isNull()
    }

    @Test
    fun `stored subscription no longer present - nothing, never the slot's new occupant`() {
        // SIM B was swapped for a new SIM in the same slot: the platform
        // minted subscription 30 for it, so the old id 20 matches nothing.
        val swapped = listOf(simA, SimInfo(subscriptionId = 30, slotIndex = 1, displayName = "Carrier C"))
        assertThat(BubbleSimIndicators.forMessage(swapped, subscriptionId = 20)).isNull()
        // Removed outright (dual-SIM phone with a third, now-absent SIM's message).
        assertThat(BubbleSimIndicators.forMessage(dualSim, subscriptionId = 99)).isNull()
    }

    @Test
    fun `the mark IS the details dialog's SIM row - one resolution rule`() {
        for (id in listOf(10, 20, 99, null)) {
            assertThat(BubbleSimIndicators.forMessage(dualSim, id))
                .isEqualTo(MessageDetails.simRowFor(dualSim, id))
        }
    }

    @Test
    fun `both directions - a received message is marked with the SIM that received it`() {
        val incoming = entity(outgoing = false, subscriptionId = 10).toConversationItem(Json, english)
        val outgoing = entity(outgoing = true, subscriptionId = 20).toConversationItem(Json, english)
        assertThat(BubbleSimIndicators.forItem(incoming, dualSim)!!.slot).isEqualTo(1)
        assertThat(BubbleSimIndicators.forItem(outgoing, dualSim)!!.slot).isEqualTo(2)
        // The same rows through the dialog's rule.
        assertThat(BubbleSimIndicators.forItem(incoming, dualSim))
            .isEqualTo(MessageDetails.simRowFor(dualSim, 10))
    }

    @Test
    fun `item view - no backing row or no subscription on it means no mark`() {
        assertThat(
            BubbleSimIndicators.forItem(ConversationItem(id = 1, body = "", timestamp = 0, outgoing = true), dualSim),
        ).isNull()
        val unrecorded = entity(outgoing = false, subscriptionId = null).toConversationItem(Json, english)
        assertThat(BubbleSimIndicators.forItem(unrecorded, dualSim)).isNull()
        // Single-SIM device: the item view gates the same way.
        val known = entity(outgoing = true, subscriptionId = 10).toConversationItem(Json, english)
        assertThat(BubbleSimIndicators.forItem(known, listOf(simA))).isNull()
    }
}
