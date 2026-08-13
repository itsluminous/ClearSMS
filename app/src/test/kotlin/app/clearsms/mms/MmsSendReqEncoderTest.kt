package app.clearsms.mms

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `m-send-req` encoding fixtures: exact header bytes and ordering, the
 * multipart body layout, per-part Content-IDs - and a full round-trip
 * through the wave-1 [MmsRetrieveConfParser] (the message-type byte is
 * flipped to m-retrieve-conf, everything else is byte-identical between
 * the two PDU shapes, so the parser exercises the same WSP encoding).
 */
class MmsSendReqEncoderTest {
    private val jpeg = MmsPart("image/jpeg", "photo.jpg", byteArrayOf(1, 2, 3, 4))

    private fun request(
        text: String = "hello there",
        attachments: List<MmsPart> = listOf(jpeg),
    ) = MmsSendReq(
        to = "+15551234567",
        transactionId = "T1",
        text = text,
        attachments = attachments,
        dateSeconds = 1_700_000_000L,
    )

    @Test
    fun `headers are ordered with message-type first and content-type last`() {
        val pdu = MmsSendReqEncoder.encode(request())

        // X-Mms-Message-Type: m-send-req.
        assertThat(pdu[0].toInt() and 0xFF).isEqualTo(0x8C)
        assertThat(pdu[1].toInt() and 0xFF).isEqualTo(0x80)
        // X-Mms-Transaction-ID text-string.
        assertThat(pdu[2].toInt() and 0xFF).isEqualTo(0x98)
        assertThat(pdu[3].toInt().toChar()).isEqualTo('T')
        assertThat(pdu[4].toInt().toChar()).isEqualTo('1')
        assertThat(pdu[5].toInt()).isEqualTo(0)
        // X-Mms-MMS-Version 1.2 as a short-integer.
        assertThat(pdu[6].toInt() and 0xFF).isEqualTo(0x8D)
        assertThat(pdu[7].toInt() and 0xFF).isEqualTo(0x92)
        // Content-Type (multipart.mixed, constrained form) appears exactly
        // once and every other header byte precedes it.
        val contentTypeAt = pdu.indices.first { pdu[it].toInt() and 0xFF == 0x84 && pdu[it + 1].toInt() and 0xFF == 0xA3 }
        val toAt = pdu.indices.first { pdu[it].toInt() and 0xFF == 0x97 }
        assertThat(toAt).isLessThan(contentTypeAt)
    }

    @Test
    fun `from uses the insert-address token`() {
        val pdu = MmsSendReqEncoder.encode(request())
        val fromAt = pdu.indices.first { pdu[it].toInt() and 0xFF == 0x89 }
        // value-length 1, insert-address-token.
        assertThat(pdu[fromAt + 1].toInt()).isEqualTo(1)
        assertThat(pdu[fromAt + 2].toInt() and 0xFF).isEqualTo(0x81)
    }

    @Test
    fun `round-trips through the wave-1 parser - text, recipient and attachment survive`() {
        val pdu = MmsSendReqEncoder.encode(request())
        val parsed = MmsRetrieveConfParser.parse(asRetrieveConf(pdu))

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.text).isEqualTo("hello there")
        assertThat(parsed.recipients).containsExactly("+15551234567")
        assertThat(parsed.attachments).hasSize(1)
        assertThat(parsed.attachments[0].mimeType).isEqualTo("image/jpeg")
        assertThat(parsed.attachments[0].fileName).isEqualTo("photo.jpg")
        assertThat(parsed.attachments[0].data).isEqualTo(byteArrayOf(1, 2, 3, 4))
    }

    @Test
    fun `text part is encoded before attachment parts`() {
        val pdu = MmsSendReqEncoder.encode(request())
        val body = String(pdu, Charsets.ISO_8859_1)
        // The text part's Content-ID precedes the attachment's.
        assertThat(body.indexOf("<text_0>")).isGreaterThan(-1)
        assertThat(body.indexOf("<part_0>")).isGreaterThan(body.indexOf("<text_0>"))
        assertThat(body.indexOf("hello there")).isLessThan(body.indexOf("photo.jpg"))
    }

    @Test
    fun `every part carries a quoted content-id header`() {
        val pdu = MmsSendReqEncoder.encode(request(attachments = listOf(jpeg, MmsPart("image/png", "b.png", byteArrayOf(9)))))
        val body = String(pdu, Charsets.ISO_8859_1)
        assertThat(body).contains("\u00C0\"<text_0>")
        assertThat(body).contains("\u00C0\"<part_0>")
        assertThat(body).contains("\u00C0\"<part_1>")
    }

    @Test
    fun `attachment-only message has no text part`() {
        val pdu = MmsSendReqEncoder.encode(request(text = ""))
        val parsed = MmsRetrieveConfParser.parse(asRetrieveConf(pdu))

        assertThat(parsed!!.text).isEmpty()
        assertThat(parsed.attachments).hasSize(1)
        assertThat(String(pdu, Charsets.ISO_8859_1)).doesNotContain("<text_0>")
    }

    @Test
    fun `unknown mime types are encoded as text-strings and survive the round trip`() {
        val pdf = MmsPart("application/pdf", "doc.pdf", byteArrayOf(7, 7))
        val pdu = MmsSendReqEncoder.encode(request(attachments = listOf(pdf)))
        val parsed = MmsRetrieveConfParser.parse(asRetrieveConf(pdu))

        assertThat(parsed!!.attachments.single().mimeType).isEqualTo("application/pdf")
        assertThat(parsed.attachments.single().fileName).isEqualTo("doc.pdf")
    }

    /**
     * Flips the X-Mms-Message-Type value (pdu[1]) from m-send-req (0x80)
     * to m-retrieve-conf (0x84) so the wave-1 parser accepts the PDU; all
     * other bytes are the encoder's own output.
     */
    private fun asRetrieveConf(pdu: ByteArray): ByteArray = pdu.copyOf().also { it[1] = 0x84.toByte() }
}
