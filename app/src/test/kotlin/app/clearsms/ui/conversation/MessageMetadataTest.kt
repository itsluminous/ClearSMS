package app.clearsms.ui.conversation

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId

/** Tap-to-reveal metadata: timestamp formatting and the single-expansion toggle. */
class MessageMetadataTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    // 2026-07-26 16:59 IST
    private val afternoon = 1_785_065_340_000L

    @Test
    fun `24-hour devices get a 24-hour timestamp`() {
        assertThat(MessageMetadata.timestampLabel(afternoon, is24Hour = true, zone = zone))
            .isEqualTo("26 Jul 2026, 16:59")
    }

    @Test
    fun `12-hour devices get an am-pm timestamp`() {
        assertThat(MessageMetadata.timestampLabel(afternoon, is24Hour = false, zone = zone))
            .isEqualTo("26 Jul 2026, 4:59 pm")
    }

    @Test
    fun `morning times keep the am marker and no leading zero`() {
        val morning = afternoon - 8 * 60 * 60 * 1000 // 08:59 IST
        assertThat(MessageMetadata.timestampLabel(morning, is24Hour = false, zone = zone))
            .isEqualTo("26 Jul 2026, 8:59 am")
        assertThat(MessageMetadata.timestampLabel(morning, is24Hour = true, zone = zone))
            .isEqualTo("26 Jul 2026, 08:59")
    }

    @Test
    fun `tapping a collapsed message expands it`() {
        assertThat(MessageMetadata.onTap(expandedId = null, tappedId = 7L, selectionActive = false))
            .isEqualTo(7L)
    }

    @Test
    fun `tapping the expanded message collapses it`() {
        assertThat(MessageMetadata.onTap(expandedId = 7L, tappedId = 7L, selectionActive = false))
            .isNull()
    }

    @Test
    fun `tapping another message moves the single expansion`() {
        assertThat(MessageMetadata.onTap(expandedId = 7L, tappedId = 9L, selectionActive = false))
            .isEqualTo(9L)
    }

    @Test
    fun `taps in selection mode leave the expansion untouched`() {
        assertThat(MessageMetadata.onTap(expandedId = 7L, tappedId = 9L, selectionActive = true))
            .isEqualTo(7L)
        assertThat(MessageMetadata.onTap(expandedId = null, tappedId = 9L, selectionActive = true))
            .isNull()
    }
}
