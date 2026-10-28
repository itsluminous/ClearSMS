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

    /** A notification whose Content-Location is the given raw octets (NUL appended). */
    private fun notificationWithRawLocation(raw: ByteArray): ByteArray =
        byteArrayOf(
            MmsPduFixtures.H_MESSAGE_TYPE.toByte(),
            MmsPduFixtures.TYPE_NOTIFICATION_IND.toByte(),
        ) +
            byteArrayOf(MmsPduFixtures.H_TRANSACTION_ID.toByte()) + MmsPduFixtures.text("T7") +
            byteArrayOf(MmsPduFixtures.H_CONTENT_LOCATION.toByte()) + raw + byteArrayOf(0)

    @Test
    fun `a quoted content location - the WSP Quote octet - is read without the quote`() {
        // OMA-TS-MMS-ENC 7.3.10: Content-location-value = Uri-value =
        // Text-string; WAP-230-WSP 8.4.2.1: Text-string = [Quote] *TEXT
        // End-of-string, Quote = <Octet 127>. The quote is not content.
        val pdu = notificationWithRawLocation(byteArrayOf(0x7F) + "http://mmsc.example.com/msg/9".toByteArray(Charsets.US_ASCII))

        assertThat(MmsNotificationParser.parse(pdu)!!.contentLocation).isEqualTo("http://mmsc.example.com/msg/9")
    }

    @Test
    fun `high octets in a content location decode as UTF-8 like the platform's own parser, not as U+FFFD`() {
        // Not a legal RFC 2616 URI, but "service user MAY use a different
        // format" (MMS-ENC 7.3.10) and the platform's PduParser keeps every
        // octet 128..255 and decodes with the default charset (UTF-8). A
        // reader that decoded US-ASCII handed the platform a different URL
        // from the one every other SMS app would hand it.
        val raw = "http://mmsc.example.com/msg/caf\u00e9".toByteArray(Charsets.UTF_8)

        val parsed = MmsNotificationParser.parse(notificationWithRawLocation(raw))!!

        assertThat(parsed.contentLocation).isEqualTo("http://mmsc.example.com/msg/caf\u00e9")
        assertThat(parsed.contentLocation).doesNotContain("\uFFFD")
        assertThat(MmsUrlShape.of(parsed.contentLocation).nonAscii).isTrue()
    }

    @Test
    fun `control octets inside a content location are dropped, as the platform drops them`() {
        // RFC 2616 TEXT excludes CTLs; the platform's getWapString skips them
        // (keeping HT/CR/LF). A stray 0x01 or a mid-string 0x7F must not
        // survive into the URL handed to the platform.
        val raw = "http://mmsc.example.com/\u0001ms\u007Fg/3".toByteArray(Charsets.ISO_8859_1)

        val parsed = MmsNotificationParser.parse(notificationWithRawLocation(raw))!!

        assertThat(parsed.contentLocation).isEqualTo("http://mmsc.example.com/msg/3")
    }

    @Test
    fun `a plain ASCII content location is byte-for-byte what the carrier sent`() {
        // The legal shape (RFC 2616 URI): the common case, and the one the
        // other encodings must reduce to.
        val url = "http://mms.example.net:8004/servlets/mms?message-id=aBc-123_x%2F&t=1"

        assertThat(MmsNotificationParser.parse(MmsPduFixtures.notificationInd(contentLocation = url))!!.contentLocation).isEqualTo(url)
    }

    @Test
    fun `a well-formed MMSC URL with a message-id query value is extracted byte-identically - the id is not dropped`() {
        // Issue #135, fourth round: two retrieve URLs of exactly 44
        // characters raised the hypothesis that our parser hands the
        // platform `...?message-id=` with the value gone. The raw octets of
        // a Verizon-shaped Content-Location (RFC 1918 host) go in; the very
        // same string must come out - every byte of the query value
        // included, and the shape must count the value's length.
        val url = "http://10.1.2.3/servlets/mms?message-id=ABC123"
        val raw = url.toByteArray(Charsets.US_ASCII)
        assertThat(raw.size).isEqualTo(url.length)

        val parsed = MmsNotificationParser.parse(notificationWithRawLocation(raw))!!

        assertThat(parsed.contentLocation).isEqualTo(url)
        assertThat(parsed.contentLocation.toByteArray(Charsets.US_ASCII)).isEqualTo(raw)
        assertThat(parsed.contentLocation).endsWith("?message-id=ABC123")
        val shape = MmsUrlShape.of(parsed.contentLocation)
        assertThat(shape.length).isEqualTo(46)
        assertThat(shape.query).containsExactly("message-id" to 6)
        assertThat(shape.mask).isEqualTo("/xxxxxxxx/xxx")
    }

    @Test
    fun `every TEXT octet of a query value survives - no byte after the equals sign is a terminator`() {
        // The whole printable range after `message-id=`: if the reader ever
        // treated `=`, `&`, a digit, a `%` or a `#` as the end of the string
        // instead of the NUL, this pins which one. (0x7F is a CTL and is
        // legitimately dropped - see the control-octet test above.)
        val value = (0x20..0x7E).map { it.toChar() }.joinToString("")
        val url = "http://10.1.2.3/servlets/mms?message-id=$value"

        val parsed = MmsNotificationParser.parse(notificationWithRawLocation(url.toByteArray(Charsets.ISO_8859_1)))!!

        assertThat(parsed.contentLocation).isEqualTo(url)
        assertThat(parsed.contentLocation.length).isEqualTo(url.length)
    }

    @Test
    fun `a content location that is the LAST header is still read whole - the NUL is the only terminator`() {
        // The fixture already places Content-Location last; here it is
        // followed by another header too, so a reader that stopped early
        // or ran into the next field would mis-read one of the two.
        val url = "http://10.1.2.3/servlets/mms?message-id=ABC123"
        val pdu =
            byteArrayOf(
                MmsPduFixtures.H_MESSAGE_TYPE.toByte(),
                MmsPduFixtures.TYPE_NOTIFICATION_IND.toByte(),
            ) +
                byteArrayOf(MmsPduFixtures.H_CONTENT_LOCATION.toByte()) + MmsPduFixtures.text(url) +
                byteArrayOf(MmsPduFixtures.H_TRANSACTION_ID.toByte()) + MmsPduFixtures.text("T8")

        val parsed = MmsNotificationParser.parse(pdu)!!

        assertThat(parsed.contentLocation).isEqualTo(url)
        assertThat(parsed.transactionId).isEqualTo("T8")
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
