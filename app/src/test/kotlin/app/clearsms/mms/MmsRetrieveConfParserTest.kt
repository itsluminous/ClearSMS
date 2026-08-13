package app.clearsms.mms

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * m-retrieve-conf parsing against synthetic fixtures built byte-by-byte
 * from the OMA MMS encapsulation spec (see [MmsPduFixtures]).
 */
class MmsRetrieveConfParserTest {
    private val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)

    @Test
    fun `text-only message yields the body and no attachments`() {
        val pdu = MmsPduFixtures.retrieveConf(entries = arrayOf(MmsPduFixtures.textPart("Hello from MMS")))

        val parsed = MmsRetrieveConfParser.parse(pdu)

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.text).isEqualTo("Hello from MMS")
        assertThat(parsed.attachments).isEmpty()
        assertThat(parsed.sender).isEqualTo("+15551234567")
    }

    @Test
    fun `image plus text yields body and one image attachment, smil ignored`() {
        val pdu =
            MmsPduFixtures.retrieveConf(
                entries =
                    arrayOf(
                        MmsPduFixtures.smilPart(),
                        MmsPduFixtures.textPart("Look at this"),
                        MmsPduFixtures.jpegPart("photo.jpg", jpegBytes),
                    ),
            )

        val parsed = MmsRetrieveConfParser.parse(pdu)!!

        assertThat(parsed.text).isEqualTo("Look at this")
        assertThat(parsed.attachments).hasSize(1)
        val image = parsed.attachments.single()
        assertThat(image.mimeType).isEqualTo("image/jpeg")
        assertThat(image.fileName).isEqualTo("photo.jpg")
        assertThat(image.isImage).isTrue()
        assertThat(image.data).isEqualTo(jpegBytes)
    }

    @Test
    fun `multi-image message keeps every image in order`() {
        val pdu =
            MmsPduFixtures.retrieveConf(
                entries =
                    arrayOf(
                        MmsPduFixtures.jpegPart("a.jpg", byteArrayOf(1)),
                        MmsPduFixtures.jpegPart("b.jpg", byteArrayOf(2)),
                        MmsPduFixtures.jpegPart("c.jpg", byteArrayOf(3)),
                    ),
            )

        val parsed = MmsRetrieveConfParser.parse(pdu)!!

        assertThat(parsed.text).isEmpty()
        assertThat(parsed.attachments.map { it.fileName }).containsExactly("a.jpg", "b.jpg", "c.jpg").inOrder()
    }

    @Test
    fun `unknown part type is kept as a named blob`() {
        val vcard =
            MmsPduFixtures.multipartEntry(
                MmsPduFixtures.generalContentType(MmsPduFixtures.text("text/x-vcard"), MmsPduFixtures.nameParam("contact.vcf")),
                "BEGIN:VCARD".toByteArray(),
            )
        val pdu = MmsPduFixtures.retrieveConf(entries = arrayOf(vcard))

        val parsed = MmsRetrieveConfParser.parse(pdu)!!

        val blob = parsed.attachments.single()
        assertThat(blob.mimeType).isEqualTo("text/x-vcard")
        assertThat(blob.fileName).isEqualTo("contact.vcf")
        assertThat(blob.isImage).isFalse()
    }

    @Test
    fun `group recipients are collected from To headers`() {
        val pdu =
            MmsPduFixtures.retrieveConf(
                recipients = listOf("+15550001111/TYPE=PLMN", "+15550002222/TYPE=PLMN"),
                entries = arrayOf(MmsPduFixtures.textPart("group hello")),
            )

        val parsed = MmsRetrieveConfParser.parse(pdu)!!

        assertThat(parsed.recipients).containsExactly("+15550001111", "+15550002222").inOrder()
    }

    @Test
    fun `truncated pdu never throws and a corrupt tail keeps decoded parts`() {
        val pdu =
            MmsPduFixtures.retrieveConf(
                entries =
                    arrayOf(
                        MmsPduFixtures.textPart("kept"),
                        MmsPduFixtures.jpegPart("photo.jpg", jpegBytes),
                    ),
            )
        for (len in 0 until pdu.size) {
            MmsRetrieveConfParser.parse(pdu.copyOf(len)) // must not throw
        }
        // Cutting inside the SECOND part's data keeps the first (text) part.
        val cut = MmsRetrieveConfParser.parse(pdu.copyOf(pdu.size - 3))
        assertThat(cut).isNotNull()
        assertThat(cut!!.text).isEqualTo("kept")
        assertThat(cut.attachments).isEmpty()
    }

    @Test
    fun `junk bytes and wrong message type return null`() {
        assertThat(MmsRetrieveConfParser.parse(ByteArray(0))).isNull()
        assertThat(MmsRetrieveConfParser.parse(byteArrayOf(0x01, 0x02, 0x03))).isNull()
        assertThat(MmsRetrieveConfParser.parse(MmsPduFixtures.notificationInd())).isNull()
    }

    @Test
    fun `single-part non-multipart body is one attachment`() {
        // Content-Type image/jpeg (constrained short-integer 0x1E), body raw.
        val pdu =
            byteArrayOf(
                MmsPduFixtures.H_MESSAGE_TYPE.toByte(),
                MmsPduFixtures.TYPE_RETRIEVE_CONF.toByte(),
                MmsPduFixtures.H_CONTENT_TYPE.toByte(),
            ) + MmsPduFixtures.shortInt(0x1E) + jpegBytes

        val parsed = MmsRetrieveConfParser.parse(pdu)!!

        assertThat(parsed.attachments.single().mimeType).isEqualTo("image/jpeg")
        assertThat(parsed.attachments.single().data).isEqualTo(jpegBytes)
    }
}
