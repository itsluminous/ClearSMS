package app.clearsms.ui.conversation

import app.clearsms.data.db.DeliveryStatus
import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.MessageSortOrder
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Bubble direction is derived from the PERSISTED [MessageEntity.isOutgoing]
 * flag - not from session state - so sent messages stay right-aligned with
 * their status after an app restart.
 */
class ConversationItemMappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun entity(
        outgoing: Boolean,
        status: DeliveryStatus? = null,
    ) = MessageEntity(
        id = 5,
        threadId = 1,
        sender = "9876543210",
        normalizedSender = "9876543210",
        body = "hello",
        timestamp = 1_000,
        category = Category.PERSONAL,
        isOutgoing = outgoing,
        deliveryStatus = status,
    )

    @Test
    fun `outgoing rows map to right-aligned bubbles carrying their status`() {
        val item = entity(outgoing = true, status = DeliveryStatus.DELIVERED).toConversationItem(json)

        assertThat(item.outgoing).isTrue()
        assertThat(item.deliveryStatus).isEqualTo(DeliveryStatus.DELIVERED)
    }

    @Test
    fun `incoming rows map to left-aligned bubbles without a status`() {
        val item = entity(outgoing = false).toConversationItem(json)

        assertThat(item.outgoing).isFalse()
        assertThat(item.deliveryStatus).isNull()
    }

    @Test
    fun `a stray status on an incoming row is never surfaced`() {
        val item = entity(outgoing = false, status = DeliveryStatus.SENT).toConversationItem(json)

        assertThat(item.deliveryStatus).isNull()
    }

    @Test
    fun `the bubble's instant follows the sort order - received by default, sent when known under SENT`() {
        // The date separators and time labels read ConversationItem.timestamp,
        // so under the sent sort they show the SAME instant the row is
        // ordered by; a row without a sent time keeps its received time.
        val withSent = entity(outgoing = false).copy(timestamp = 5_000L, dateSent = 1_000L)
        val withoutSent = entity(outgoing = false).copy(timestamp = 5_000L, dateSent = null)

        assertThat(withSent.toConversationItem(json).timestamp).isEqualTo(5_000L)
        assertThat(withSent.toConversationItem(json, sortOrder = MessageSortOrder.RECEIVED).timestamp).isEqualTo(5_000L)
        assertThat(withSent.toConversationItem(json, sortOrder = MessageSortOrder.SENT).timestamp).isEqualTo(1_000L)
        assertThat(withoutSent.toConversationItem(json, sortOrder = MessageSortOrder.SENT).timestamp).isEqualTo(5_000L)
    }
}
