package app.clearsms.mms

/**
 * The content of an outgoing MMS reduced to what an `m-send-req` PDU
 * needs: the recipient, the compose text and the attachment parts.
 */
data class MmsSendReq(
    /** Destination address; the `/TYPE=PLMN` qualifier is appended on encode. */
    val to: String,
    /** X-Mms-Transaction-ID - unique per submission attempt. */
    val transactionId: String,
    /** The compose-field text; blank means an attachment-only message. */
    val text: String,
    /** Attachment parts, encoded AFTER the text part. */
    val attachments: List<MmsPart>,
    /** Message date, in epoch seconds. */
    val dateSeconds: Long,
)

/**
 * Minimal clean-room encoder for `m-send-req` per the public OMA MMS
 * encapsulation spec (OMA-TS-MMS-ENC / WAP-230-WSP), built from the same
 * WSP primitives the wave-1 parsers read with.
 *
 * Layout choices, all spec-conformant and chosen for interoperability:
 * - Header order: message-type, transaction-id, version, date, from, to,
 *   then Content-Type LAST (the spec requires the body to follow it).
 * - From uses the insert-address-token: the MMSC stamps the sender from
 *   the bearer, which is more reliable than self-reporting a number.
 * - The body is `application/vnd.wap.multipart.mixed` (no SMIL
 *   presentation part - receivers render parts directly, exactly like the
 *   app's own wave-1 parser does).
 * - Parts are ordered TEXT FIRST, then attachments; every part carries a
 *   Content-ID and, when named, a Content-Location part header.
 */
object MmsSendReqEncoder {
    private const val MESSAGE_TYPE_SEND_REQ = 0x80

    /** X-Mms-MMS-Version 1.2, encoded as a short-integer (major 1, minor 2). */
    private const val MMS_VERSION_1_2 = 0x12

    /** From: insert-address-token - the MMSC fills the sender in. */
    private const val INSERT_ADDRESS_TOKEN = 0x81

    /** application/vnd.wap.multipart.mixed well-known media code. */
    private const val MULTIPART_MIXED = 0x23

    /** UTF-8 IANA MIBenum, carried as the text part's charset parameter. */
    private const val CHARSET_UTF8 = 106L

    /** Well-known WSP part-header field numbers (WAP-230 header assignments). */
    private const val HEADER_CONTENT_LOCATION = 0x0E
    private const val HEADER_CONTENT_ID = 0x40

    /** Content-Type parameter tokens: 0x81 Charset, 0x85 Name. */
    private const val PARAM_CHARSET = 0x81
    private const val PARAM_NAME = 0x85

    fun encode(request: MmsSendReq): ByteArray {
        val writer = WspWriter()

        // Headers. X-Mms field bytes are the assigned numbers | 0x80,
        // matching the constants the wave-1 parser reads (MmsHeaders).
        writer.writeByte(MmsHeaders.MESSAGE_TYPE)
        writer.writeByte(MESSAGE_TYPE_SEND_REQ)
        writer.writeByte(MmsHeaders.TRANSACTION_ID)
        writer.writeTextString(request.transactionId)
        writer.writeByte(MMS_VERSION_HEADER)
        writer.writeShortInteger(MMS_VERSION_1_2)
        writer.writeByte(MmsHeaders.DATE)
        writer.writeLongInteger(request.dateSeconds)
        writer.writeByte(MmsHeaders.FROM)
        writer.writeValueLength(1)
        writer.writeByte(INSERT_ADDRESS_TOKEN)
        writer.writeByte(MmsHeaders.TO)
        writer.writeTextString("${request.to}/TYPE=PLMN")
        // Content-Type is last; the multipart body follows immediately.
        writer.writeByte(MmsHeaders.CONTENT_TYPE)
        writer.writeShortInteger(MULTIPART_MIXED)

        // Body: uintvar part count, then each part as headers-len,
        // data-len, headers (Content-Type first), data. Text part FIRST.
        val parts =
            buildList {
                if (request.text.isNotBlank()) add(textPart(request.text))
                request.attachments.forEachIndexed { index, part ->
                    add(attachmentPart(part, index))
                }
            }
        writer.writeUintvar(parts.size.toLong())
        for ((headers, data) in parts) {
            writer.writeUintvar(headers.size.toLong())
            writer.writeUintvar(data.size.toLong())
            writer.writeBytes(headers)
            writer.writeBytes(data)
        }
        return writer.toByteArray()
    }

    /** text/plain; charset=utf-8 part with a Content-ID. */
    private fun textPart(text: String): Pair<ByteArray, ByteArray> {
        val headers = WspWriter()
        // Content-type general form: value-length, well-known media,
        // charset parameter.
        val contentType = WspWriter()
        contentType.writeShortInteger(WELL_KNOWN_TEXT_PLAIN)
        contentType.writeByte(PARAM_CHARSET)
        contentType.writeIntegerValue(CHARSET_UTF8)
        headers.writeValueLength(contentType.size)
        headers.writeBytes(contentType.toByteArray())
        writeContentId(headers, "text_0")
        return headers.toByteArray() to text.toByteArray(Charsets.UTF_8)
    }

    /** Typed attachment part with name, Content-ID and Content-Location. */
    private fun attachmentPart(
        part: MmsPart,
        index: Int,
    ): Pair<ByteArray, ByteArray> {
        val headers = WspWriter()
        val name = part.fileName?.takeIf { it.isNotBlank() }
        val contentType = WspWriter()
        val wellKnown = wellKnownMediaCode(part.mimeType)
        if (wellKnown != null) {
            contentType.writeShortInteger(wellKnown)
        } else {
            contentType.writeTextString(part.mimeType)
        }
        if (name != null) {
            contentType.writeByte(PARAM_NAME)
            contentType.writeTextString(name)
        }
        headers.writeValueLength(contentType.size)
        headers.writeBytes(contentType.toByteArray())
        writeContentId(headers, "part_$index")
        if (name != null) {
            headers.writeByte(HEADER_CONTENT_LOCATION or 0x80)
            headers.writeTextString(name)
        }
        return headers.toByteArray() to part.data
    }

    /** Content-ID part header: well-known field 0x40, quoted `<cid>` value. */
    private fun writeContentId(
        headers: WspWriter,
        cid: String,
    ) {
        headers.writeByte(HEADER_CONTENT_ID or 0x80)
        headers.writeQuotedString("<$cid>")
    }

    /** Reverse of the wave-1 parser's well-known media table. */
    private fun wellKnownMediaCode(mimeType: String): Int? =
        when (mimeType) {
            "text/plain" -> 0x03
            "image/gif" -> 0x1D
            "image/jpeg" -> 0x1E
            "image/tiff" -> 0x1F
            "image/png" -> 0x20
            else -> null
        }

    private const val WELL_KNOWN_TEXT_PLAIN = 0x03

    /** X-Mms-MMS-Version header field byte (assigned number 0x0D | 0x80). */
    private const val MMS_VERSION_HEADER = 0x8D
}
