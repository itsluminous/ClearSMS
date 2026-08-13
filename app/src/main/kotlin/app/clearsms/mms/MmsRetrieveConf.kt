package app.clearsms.mms

/** One decoded attachment part of a retrieved MMS. */
data class MmsPart(
    /** Lower-cased mime type, e.g. `image/jpeg`; `application/octet-stream` when unknown. */
    val mimeType: String,
    /** The part's declared name/filename parameter, when it carried one. */
    val fileName: String?,
    val data: ByteArray,
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")

    override fun equals(other: Any?): Boolean =
        other is MmsPart && other.mimeType == mimeType && other.fileName == fileName && other.data.contentEquals(data)

    override fun hashCode(): Int = 31 * (31 * mimeType.hashCode() + (fileName?.hashCode() ?: 0)) + data.contentHashCode()
}

/**
 * The content of an MMS `m-retrieve-conf` PDU (the message actually fetched
 * from the carrier MMSC) reduced to what the app stores: the text, the
 * attachments, and the addressing needed to attribute a group message.
 */
data class MmsRetrieveConf(
    /** Originating address (From), or null when absent. */
    val sender: String?,
    /** To/Cc addresses on the message - the group recipients list. */
    val recipients: List<String>,
    /** All text/plain parts concatenated in order; empty for an image-only MMS. */
    val text: String,
    /** Non-text, non-SMIL parts: images and named blobs. */
    val attachments: List<MmsPart>,
)

/**
 * Minimal, defensive parser for `m-retrieve-conf` per the public OMA MMS
 * encapsulation spec (OMA-TS-MMS-ENC / WAP-230-WSP). SMIL presentation
 * parts are ignored (the app renders parts directly); text/plain parts
 * become the body; every other part is kept as a typed attachment.
 * Malformed input never throws: a broken header block yields null, and a
 * broken part terminates part parsing, keeping the parts already decoded.
 */
object MmsRetrieveConfParser {
    private const val MESSAGE_TYPE_RETRIEVE_CONF = 0x84
    private const val SMIL_MIME = "application/smil"

    fun parse(pdu: ByteArray): MmsRetrieveConf? =
        try {
            parseOrThrow(WspReader(pdu))
        } catch (_: Exception) {
            null
        }

    private fun parseOrThrow(reader: WspReader): MmsRetrieveConf? {
        var messageType: Int? = null
        var sender: String? = null
        val recipients = mutableListOf<String>()
        var contentType: ContentType? = null

        while (reader.hasMore()) {
            val field = reader.readByte()
            if (field < 0x80) return null
            when (field) {
                MmsHeaders.MESSAGE_TYPE -> messageType = reader.readByte()
                MmsHeaders.FROM -> sender = MmsHeaders.readFrom(reader)
                MmsHeaders.TO, MmsHeaders.CC, MmsHeaders.BCC ->
                    MmsHeaders
                        .stripAddressType(reader.readEncodedString())
                        .takeIf { it.isNotBlank() }
                        ?.let(recipients::add)
                MmsHeaders.CONTENT_TYPE -> {
                    // Per the spec Content-Type is the last header; the
                    // message body starts right after it.
                    contentType = readContentType(reader)
                    break
                }
                else -> reader.skipFieldValue()
            }
            if (messageType != null && messageType != MESSAGE_TYPE_RETRIEVE_CONF) return null
        }

        if (messageType != MESSAGE_TYPE_RETRIEVE_CONF) return null
        val type = contentType ?: return null

        val parts =
            if (type.mimeType.startsWith("application/vnd.wap.multipart")) {
                readMultipart(reader)
            } else {
                // Single-part body: everything after the headers is the data.
                listOf(RawPart(type, reader.readBytes(reader.remaining)))
            }

        val text =
            parts
                .filter { it.type.mimeType == "text/plain" }
                .joinToString(separator = "\n") { String(it.data, charsetFor(it.type.charset)) }
                .trim()
        val attachments =
            parts
                .filter { it.type.mimeType != "text/plain" && it.type.mimeType != SMIL_MIME }
                .map { MmsPart(it.type.mimeType, it.type.fileName, it.data) }
        return MmsRetrieveConf(
            sender = sender,
            recipients = recipients,
            text = text,
            attachments = attachments,
        )
    }

    private class RawPart(
        val type: ContentType,
        val data: ByteArray,
    )

    /**
     * WSP multipart body: uintvar entry count, then per entry a uintvar
     * headers length, a uintvar data length, the part's Content-Type (plus
     * any other part headers) inside the headers region, and the data. A
     * part that fails to decode ends the loop; parts already decoded
     * survive.
     */
    private fun readMultipart(reader: WspReader): List<RawPart> {
        val parts = mutableListOf<RawPart>()
        try {
            val count = reader.readUintvar()
            for (i in 0 until count) {
                val headersLen = reader.readUintvar().toInt()
                val dataLen = reader.readUintvar().toInt()
                val headers = reader.subReader(headersLen)
                reader.skip(headersLen)
                val contentType = readContentType(headers)
                val data = reader.readBytes(dataLen)
                parts += RawPart(contentType, data)
            }
        } catch (_: Exception) {
            // Truncated or corrupt tail: keep what decoded cleanly.
        }
        return parts
    }

    private class ContentType(
        val mimeType: String,
        val fileName: String?,
        val charset: Long?,
    )

    /**
     * WSP Content-type-value: constrained form (a well-known short-integer
     * or a text-string media type) or general form (value-length + media +
     * parameters). Captures the name/filename and charset parameters; all
     * others are skipped with the universal field-value rule.
     */
    private fun readContentType(reader: WspReader): ContentType {
        val first = reader.peek()
        if (first >= 0x80) return ContentType(wellKnownMedia(reader.readShortInteger()), fileName = null, charset = null)
        if (first > 31) return ContentType(reader.readTextString().lowercase(), fileName = null, charset = null)

        val length = reader.readValueLength()
        val sub = reader.subReader(length)
        reader.skip(length)
        val mime =
            if (sub.peek() >= 0x80) {
                wellKnownMedia(sub.readShortInteger())
            } else {
                sub.readTextString().lowercase()
            }
        var fileName: String? = null
        var charset: Long? = null
        while (sub.hasMore()) {
            when (sub.peek()) {
                // Well-known parameter tokens (short-integer encoded):
                // 0x85/0x97 Name, 0x86/0x98 Filename (0x97/0x98 are the
                // re-assigned WSP 1.4 codes for the same parameters).
                0x85, 0x97, 0x86, 0x98 -> {
                    sub.skip(1)
                    val value = sub.readTextString()
                    if (fileName == null) fileName = value.takeIf { it.isNotBlank() }
                }
                // 0x81 Charset (well-known charset MIBenum).
                0x81 -> {
                    sub.skip(1)
                    charset = sub.readIntegerValue()
                }
                else -> {
                    // Unknown parameter: skip its name, then its value.
                    sub.skipFieldValue()
                    if (sub.hasMore()) sub.skipFieldValue()
                }
            }
        }
        return ContentType(mime, fileName, charset)
    }

    /** IANA MIBenum -> charset for the values seen in real MMS traffic. */
    private fun charsetFor(mibEnum: Long?): java.nio.charset.Charset =
        when (mibEnum) {
            3L -> Charsets.US_ASCII
            4L -> Charsets.ISO_8859_1
            else -> Charsets.UTF_8
        }

    /** The WSP well-known media-type assignments the app can encounter in MMS. */
    private fun wellKnownMedia(code: Int): String =
        when (code) {
            0x03 -> "text/plain"
            0x1D -> "image/gif"
            0x1E -> "image/jpeg"
            0x1F -> "image/tiff"
            0x20 -> "image/png"
            0x21 -> "image/vnd.wap.wbmp"
            0x23 -> "application/vnd.wap.multipart.mixed"
            0x33 -> "application/vnd.wap.multipart.related"
            else -> "application/octet-stream"
        }
}
