package app.clearsms.mms

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * m-notification-ind parsing against synthetic fixtures built byte-by-byte
 * from the OMA MMS encapsulation spec (see [MmsPduFixtures]).
 */
class MmsNotificationParserTest {
    @Test
    fun `well-formed notification yields every field`() {
        val now = 1_000_000_000_000L
        val parsed = MmsNotificationParser.parse(MmsPduFixtures.notificationInd(), nowMs = now)

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.transactionId).isEqualTo("T123")
        assertThat(parsed.contentLocation).isEqualTo("http://mmsc.example.com/msg/1")
        assertThat(parsed.sender).isEqualTo("+15551234567")
        assertThat(parsed.messageSizeBytes).isEqualTo(45_678L)
        assertThat(parsed.expiryEpochMs).isEqualTo(now + 3_600_000L)
    }

    @Test
    fun `absolute expiry resolves to the epoch deadline`() {
        val pdu =
            byteArrayOf(
                MmsPduFixtures.H_MESSAGE_TYPE.toByte(),
                MmsPduFixtures.TYPE_NOTIFICATION_IND.toByte(),
            ) +
                byteArrayOf(MmsPduFixtures.H_TRANSACTION_ID.toByte()) + MmsPduFixtures.text("T9") +
                MmsPduFixtures.absoluteExpiry(1_700_000_000L) +
                byteArrayOf(MmsPduFixtures.H_CONTENT_LOCATION.toByte()) + MmsPduFixtures.text("http://x/1")

        val parsed = MmsNotificationParser.parse(pdu, nowMs = 0L)

        assertThat(parsed!!.expiryEpochMs).isEqualTo(1_700_000_000_000L)
    }

    @Test
    fun `insert-address-token From leaves the sender null`() {
        // From: value-length 1, insert-address token 0x81 (the carrier fills
        // the address at retrieve time).
        val pdu =
            byteArrayOf(
                MmsPduFixtures.H_MESSAGE_TYPE.toByte(),
                MmsPduFixtures.TYPE_NOTIFICATION_IND.toByte(),
                MmsPduFixtures.H_FROM.toByte(),
                1,
                0x81.toByte(),
            ) +
                byteArrayOf(MmsPduFixtures.H_TRANSACTION_ID.toByte()) + MmsPduFixtures.text("T5") +
                byteArrayOf(MmsPduFixtures.H_CONTENT_LOCATION.toByte()) + MmsPduFixtures.text("http://x/5")

        val parsed = MmsNotificationParser.parse(pdu)

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.sender).isNull()
    }

    @Test
    fun `truncated pdu returns null instead of throwing`() {
        val full = MmsPduFixtures.notificationInd()
        // Cut mid-header at every possible length: none may throw, and a cut
        // before the mandatory content-location must fail the parse.
        for (len in 0 until full.size) {
            MmsNotificationParser.parse(full.copyOf(len)) // must not throw
        }
        assertThat(MmsNotificationParser.parse(full.copyOf(4))).isNull()
    }

    @Test
    fun `junk bytes return null`() {
        assertThat(MmsNotificationParser.parse(ByteArray(0))).isNull()
        assertThat(MmsNotificationParser.parse(byteArrayOf(0x00, 0x01, 0x02))).isNull()
        assertThat(MmsNotificationParser.parse(ByteArray(64) { (it * 7).toByte() })).isNull()
    }

    @Test
    fun `a different message type is rejected`() {
        val pdu = MmsPduFixtures.retrieveConf(entries = arrayOf(MmsPduFixtures.textPart("hi")))
        assertThat(MmsNotificationParser.parse(pdu)).isNull()
    }

    @Test
    fun `missing content location fails the parse`() {
        val pdu =
            byteArrayOf(
                MmsPduFixtures.H_MESSAGE_TYPE.toByte(),
                MmsPduFixtures.TYPE_NOTIFICATION_IND.toByte(),
            ) + byteArrayOf(MmsPduFixtures.H_TRANSACTION_ID.toByte()) + MmsPduFixtures.text("T1")

        assertThat(MmsNotificationParser.parse(pdu)).isNull()
    }
}
