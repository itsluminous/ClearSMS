package app.clearsms.ui.conversation

import app.clearsms.R
import app.clearsms.data.db.DeliveryStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId

/** UI state mapping for scheduled message bubbles. */
class ScheduledBubbleMappingTest {
    @Test
    fun `tapping a scheduled bubble offers its schedule actions`() {
        assertThat(
            MessageMetadata.tapAction(
                selectionActive = false,
                outgoing = true,
                deliveryStatus = DeliveryStatus.SCHEDULED,
            ),
        ).isEqualTo(MessageMetadata.TapAction.OFFER_SCHEDULE_ACTIONS)
    }

    @Test
    fun `selection mode still wins over the scheduled actions`() {
        assertThat(
            MessageMetadata.tapAction(
                selectionActive = true,
                outgoing = true,
                deliveryStatus = DeliveryStatus.SCHEDULED,
            ),
        ).isEqualTo(MessageMetadata.TapAction.TOGGLE_SELECTION)
    }

    @Test
    fun `an incoming message can never offer schedule actions`() {
        assertThat(
            MessageMetadata.tapAction(
                selectionActive = false,
                outgoing = false,
                deliveryStatus = null,
            ),
        ).isEqualTo(MessageMetadata.TapAction.TOGGLE_DETAILS)
    }

    @Test
    fun `the metadata line labels a scheduled message as Scheduled`() {
        assertThat(deliveryStatusLabelRes(DeliveryStatus.SCHEDULED))
            .isEqualTo(R.string.conversation_scheduled)
    }

    @Test
    fun `picker date and time combine into the local wall-clock instant`() {
        // 15 Aug 2026 (UTC midnight from the date picker) at 14:30 in Kolkata.
        val utcDateMillis = 1_786_752_000_000L // 2026-08-15T00:00:00Z
        val zone = ZoneId.of("Asia/Kolkata")

        val at = combineDateAndTime(utcDateMillis, hour = 14, minute = 30, zone = zone)

        val local =
            java.time.Instant
                .ofEpochMilli(at)
                .atZone(zone)
        assertThat(local.toLocalDate().toString()).isEqualTo("2026-08-15")
        assertThat(local.hour).isEqualTo(14)
        assertThat(local.minute).isEqualTo(30)
    }
}
