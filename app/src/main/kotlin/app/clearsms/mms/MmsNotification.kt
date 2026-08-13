package app.clearsms.mms

/**
 * The fields of an MMS `m-notification-ind` PDU (the WAP push a carrier
 * sends to announce a waiting multimedia message) that the app needs to
 * fetch and display it. Field layout follows the public OMA MMS
 * encapsulation spec (OMA-TS-MMS-ENC).
 */
data class MmsNotification(
    /** X-Mms-Transaction-ID - correlates the notify/retrieve transaction. */
    val transactionId: String,
    /** X-Mms-Content-Location - the carrier MMSC URL holding the message. */
    val contentLocation: String,
    /**
     * The originating address from the From header, or null when the
     * carrier used the insert-address token (address supplied at retrieve
     * time). Any `/TYPE=` suffix (e.g. `/TYPE=PLMN`) is stripped.
     */
    val sender: String?,
    /** X-Mms-Message-Size in bytes, when the carrier included it. */
    val messageSizeBytes: Long?,
    /** X-Mms-Expiry resolved to an absolute epoch-millis deadline, when present. */
    val expiryEpochMs: Long?,
)

/**
 * Minimal, defensive parser for `m-notification-ind`. Anything structurally
 * broken - truncated data, junk bytes, a different message type, missing
 * mandatory fields - yields null; it must never throw.
 */
object MmsNotificationParser {
    private const val MESSAGE_TYPE_NOTIFICATION_IND = 0x82

    /**
     * @param nowMs reference clock for resolving a RELATIVE expiry
     *   (delta-seconds token) to an absolute deadline.
     */
    fun parse(
        pdu: ByteArray,
        nowMs: Long = System.currentTimeMillis(),
    ): MmsNotification? =
        try {
            parseOrThrow(WspReader(pdu), nowMs)
        } catch (_: Exception) {
            null
        }

    private fun parseOrThrow(
        reader: WspReader,
        nowMs: Long,
    ): MmsNotification? {
        var messageType: Int? = null
        var transactionId: String? = null
        var contentLocation: String? = null
        var sender: String? = null
        var messageSize: Long? = null
        var expiryMs: Long? = null

        while (reader.hasMore()) {
            val field = reader.readByte()
            // Header field names are short-integers (high bit set); anything
            // else at this position is not a valid MMS header block.
            if (field < 0x80) return null
            when (field) {
                MmsHeaders.MESSAGE_TYPE -> messageType = reader.readByte()
                MmsHeaders.TRANSACTION_ID -> transactionId = reader.readTextString()
                MmsHeaders.CONTENT_LOCATION -> contentLocation = reader.readTextString()
                MmsHeaders.FROM -> sender = MmsHeaders.readFrom(reader)
                MmsHeaders.MESSAGE_SIZE -> messageSize = reader.readLongInteger()
                MmsHeaders.EXPIRY -> expiryMs = readExpiry(reader, nowMs)
                else -> reader.skipFieldValue()
            }
            // The message type is the first header; bail out early on any
            // other PDU kind instead of decoding the rest.
            if (messageType != null && messageType != MESSAGE_TYPE_NOTIFICATION_IND) return null
        }

        if (messageType != MESSAGE_TYPE_NOTIFICATION_IND) return null
        return MmsNotification(
            transactionId = transactionId ?: return null,
            contentLocation = contentLocation ?: return null,
            sender = sender,
            messageSizeBytes = messageSize,
            expiryEpochMs = expiryMs,
        )
    }

    /**
     * X-Mms-Expiry: value-length, then a token - absolute (0x80) followed by
     * a date long-integer in epoch seconds, or relative (0x81) followed by
     * delta-seconds from now.
     */
    private fun readExpiry(
        reader: WspReader,
        nowMs: Long,
    ): Long? {
        val length = reader.readValueLength()
        val sub = reader.subReader(length)
        reader.skip(length)
        return when (sub.readByte()) {
            0x80 -> sub.readLongInteger() * 1000L
            0x81 -> nowMs + sub.readIntegerValue() * 1000L
            else -> null
        }
    }
}

/** MMS header field ids (short-integer encoded, i.e. with the 0x80 bit set). */
internal object MmsHeaders {
    const val BCC = 0x81
    const val CC = 0x82
    const val CONTENT_LOCATION = 0x83
    const val CONTENT_TYPE = 0x84
    const val DATE = 0x85
    const val EXPIRY = 0x88
    const val FROM = 0x89
    const val MESSAGE_TYPE = 0x8C
    const val MESSAGE_SIZE = 0x8E
    const val SUBJECT = 0x96
    const val TO = 0x97
    const val TRANSACTION_ID = 0x98

    /**
     * From: value-length, then address-present-token (0x80) + encoded
     * address, or insert-address-token (0x81, address filled in by the
     * carrier). Returns the bare address with any `/TYPE=` suffix removed.
     */
    fun readFrom(reader: WspReader): String? {
        val length = reader.readValueLength()
        val sub = reader.subReader(length)
        reader.skip(length)
        if (!sub.hasMore()) return null
        return when (sub.readByte()) {
            0x80 -> stripAddressType(sub.readEncodedString()).takeIf { it.isNotBlank() }
            else -> null
        }
    }

    /** Drops the WAP address qualifier, e.g. `+15551234567/TYPE=PLMN`. */
    fun stripAddressType(address: String): String = address.substringBefore("/TYPE=")
}
