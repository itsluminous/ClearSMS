package app.clearsms.mms

import java.io.ByteArrayOutputStream

/**
 * Builds synthetic MMS PDU byte arrays for the parser tests, straight from
 * the public OMA MMS encapsulation spec (OMA-TS-MMS-ENC / WAP-230-WSP).
 * Each helper documents the wire format it emits so a fixture reads as a
 * spec walkthrough rather than opaque bytes.
 */
object MmsPduFixtures {
    // MMS header field ids (short-integer form: assigned number | 0x80).
    const val H_MESSAGE_TYPE = 0x8C
    const val H_TRANSACTION_ID = 0x98
    const val H_MMS_VERSION = 0x8D
    const val H_FROM = 0x89
    const val H_TO = 0x97
    const val H_DATE = 0x85
    const val H_MESSAGE_CLASS = 0x8A
    const val H_MESSAGE_SIZE = 0x8E
    const val H_EXPIRY = 0x88
    const val H_CONTENT_LOCATION = 0x83
    const val H_CONTENT_TYPE = 0x84

    // X-Mms-Message-Type values.
    const val TYPE_NOTIFICATION_IND = 0x82
    const val TYPE_RETRIEVE_CONF = 0x84

    /** WSP uintvar: 7 bits per byte, MSB set on every byte except the last. */
    fun uintvar(value: Int): ByteArray {
        require(value >= 0)
        var v = value
        val bytes = ArrayDeque<Byte>()
        bytes.addFirst((v and 0x7F).toByte())
        v = v ushr 7
        while (v > 0) {
            bytes.addFirst(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
        return bytes.toByteArray()
    }

    /** WSP text-string: US-ASCII bytes followed by a NUL terminator. */
    fun text(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

    /** WSP short-integer: the value (< 128) with the high bit set, one byte. */
    fun shortInt(value: Int): ByteArray {
        require(value in 0..0x7F)
        return byteArrayOf((value or 0x80).toByte())
    }

    /** WSP long-integer: a short-length byte (1..30) then big-endian bytes. */
    fun longInt(value: Long): ByteArray {
        require(value >= 0)
        var v = value
        val bytes = ArrayDeque<Byte>()
        do {
            bytes.addFirst((v and 0xFF).toByte())
            v = v ushr 8
        } while (v > 0)
        return byteArrayOf(bytes.size.toByte()) + bytes.toByteArray()
    }

    /** WSP value-length: 0..30 inline, otherwise 31 followed by a uintvar. */
    fun valueLength(length: Int): ByteArray = if (length <= 30) byteArrayOf(length.toByte()) else byteArrayOf(31) + uintvar(length)

    /** `From` header value: address-present token (0x80) + text address. */
    fun fromAddress(address: String): ByteArray {
        val payload = byteArrayOf(0x80.toByte()) + text(address)
        return byteArrayOf(H_FROM.toByte()) + valueLength(payload.size) + payload
    }

    /** `X-Mms-Expiry` value: token 0x81 = relative delta-seconds. */
    fun relativeExpiry(deltaSeconds: Long): ByteArray {
        val payload = byteArrayOf(0x81.toByte()) + longInt(deltaSeconds)
        return byteArrayOf(H_EXPIRY.toByte()) + valueLength(payload.size) + payload
    }

    /** `X-Mms-Expiry` value: token 0x80 = absolute date in epoch seconds. */
    fun absoluteExpiry(epochSeconds: Long): ByteArray {
        val payload = byteArrayOf(0x80.toByte()) + longInt(epochSeconds)
        return byteArrayOf(H_EXPIRY.toByte()) + valueLength(payload.size) + payload
    }

    /** Content-type in general form: value-length + media bytes + parameter bytes. */
    fun generalContentType(
        media: ByteArray,
        vararg params: ByteArray,
    ): ByteArray {
        val payload = media + params.fold(ByteArray(0)) { acc, p -> acc + p }
        return valueLength(payload.size) + payload
    }

    /** Well-known `name` parameter (token 0x85) with a text value. */
    fun nameParam(value: String): ByteArray = byteArrayOf(0x85.toByte()) + text(value)

    /** Well-known `charset` parameter (token 0x81) with a short-integer MIBenum. */
    fun charsetParam(mibEnum: Int): ByteArray = byteArrayOf(0x81.toByte()) + shortInt(mibEnum)

    /** One multipart entry: uintvar headers length, uintvar data length, headers, data. */
    fun multipartEntry(
        contentType: ByteArray,
        data: ByteArray,
    ): ByteArray = uintvar(contentType.size) + uintvar(data.size) + contentType + data

    /** A multipart body: uintvar entry count followed by the entries. */
    fun multipartBody(vararg entries: ByteArray): ByteArray = uintvar(entries.size) + entries.fold(ByteArray(0)) { acc, e -> acc + e }

    /** A complete, well-formed m-notification-ind PDU. */
    fun notificationInd(
        transactionId: String = "T123",
        contentLocation: String = "http://mmsc.example.com/msg/1",
        sender: String? = "+15551234567/TYPE=PLMN",
        sizeBytes: Long? = 45_678L,
        expiryDeltaSeconds: Long? = 3_600L,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(H_MESSAGE_TYPE.toByte(), TYPE_NOTIFICATION_IND.toByte()))
        out.write(byteArrayOf(H_TRANSACTION_ID.toByte()) + text(transactionId))
        // MMS-Version 1.2 (short-integer 0x12): the parser must skip it.
        out.write(byteArrayOf(H_MMS_VERSION.toByte(), (0x12 or 0x80).toByte()))
        if (sender != null) out.write(fromAddress(sender))
        // Message-Class personal (octet 0x80): also skipped by the parser.
        out.write(byteArrayOf(H_MESSAGE_CLASS.toByte(), 0x80.toByte()))
        if (sizeBytes != null) out.write(byteArrayOf(H_MESSAGE_SIZE.toByte()) + longInt(sizeBytes))
        if (expiryDeltaSeconds != null) out.write(relativeExpiry(expiryDeltaSeconds))
        out.write(byteArrayOf(H_CONTENT_LOCATION.toByte()) + text(contentLocation))
        return out.toByteArray()
    }

    /** A complete m-retrieve-conf PDU with the given multipart entries. */
    fun retrieveConf(
        sender: String? = "+15551234567/TYPE=PLMN",
        recipients: List<String> = emptyList(),
        vararg entries: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(H_MESSAGE_TYPE.toByte(), TYPE_RETRIEVE_CONF.toByte()))
        out.write(byteArrayOf(H_TRANSACTION_ID.toByte()) + text("R1"))
        if (sender != null) out.write(fromAddress(sender))
        for (recipient in recipients) {
            out.write(byteArrayOf(H_TO.toByte()) + text(recipient))
        }
        out.write(byteArrayOf(H_DATE.toByte()) + longInt(1_700_000_000L))
        // Content-Type: application/vnd.wap.multipart.related, constrained
        // form (well-known code 0x33 as a short-integer).
        out.write(byteArrayOf(H_CONTENT_TYPE.toByte()) + shortInt(0x33))
        out.write(multipartBody(*entries))
        return out.toByteArray()
    }

    /** A text/plain part (well-known media 0x03) carrying UTF-8 [body]. */
    fun textPart(body: String): ByteArray =
        multipartEntry(
            generalContentType(shortInt(0x03), charsetParam(0x6A)), // 0x6A = 106 = UTF-8
            body.toByteArray(Charsets.UTF_8),
        )

    /** An image/jpeg part (well-known media 0x1E) named [name]. */
    fun jpegPart(
        name: String,
        data: ByteArray,
    ): ByteArray = multipartEntry(generalContentType(shortInt(0x1E), nameParam(name)), data)

    /** An application/smil presentation part (extension-media text form). */
    fun smilPart(): ByteArray =
        multipartEntry(
            generalContentType(text("application/smil")),
            "<smil><body/></smil>".toByteArray(Charsets.US_ASCII),
        )
}
