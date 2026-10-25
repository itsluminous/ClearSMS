package app.clearsms.mms

/** Structural failure while decoding a PDU; always caught at the parser boundary. */
internal class MalformedPduException(
    message: String,
) : Exception(message)

/**
 * Bounds-checked cursor over the WSP (Wireless Session Protocol) binary
 * primitives the MMS encapsulation format is built from, as published in the
 * open OMA specifications (OMA-TS-MMS-ENC and WAP-230-WSP). Only the handful
 * of primitives the app's minimal MMS parsers need are implemented.
 *
 * Every read is bounds-checked and throws [MalformedPduException] on
 * overrun; callers catch at the top level and treat the PDU as malformed -
 * a hostile or truncated PDU must never crash the process.
 */
internal class WspReader(
    private val data: ByteArray,
    /** Exclusive end of the readable region (sub-readers bound a part). */
    private val end: Int = data.size,
    start: Int = 0,
) {
    var position: Int = start
        private set

    val remaining: Int get() = end - position

    fun hasMore(): Boolean = position < end

    /** Unsigned value of the next byte without consuming it. */
    fun peek(): Int {
        require(position < end) { "peek past end" }
        return data[position].toInt() and 0xFF
    }

    fun readByte(): Int {
        require(position < end) { "byte past end" }
        return data[position++].toInt() and 0xFF
    }

    fun readBytes(count: Int): ByteArray {
        require(count in 0..remaining) { "bytes past end" }
        val out = data.copyOfRange(position, position + count)
        position += count
        return out
    }

    fun skip(count: Int) {
        require(count in 0..remaining) { "skip past end" }
        position += count
    }

    /** WSP uintvar: 7 bits per byte, MSB is the continuation flag, max 5 bytes. */
    fun readUintvar(): Long {
        var value = 0L
        for (i in 0 until 5) {
            val b = readByte()
            value = (value shl 7) or (b and 0x7F).toLong()
            if (b and 0x80 == 0) return value
        }
        throw MalformedPduException("uintvar longer than 5 bytes")
    }

    /** WSP short-integer: single byte with the high bit set; value is the low 7 bits. */
    fun readShortInteger(): Int {
        val b = readByte()
        require(b >= 0x80) { "not a short-integer" }
        return b and 0x7F
    }

    /** WSP long-integer: short-length (1..30) followed by that many big-endian bytes. */
    fun readLongInteger(): Long {
        val len = readByte()
        require(len in 1..30) { "bad long-integer length" }
        require(len <= 8) { "long-integer wider than 64 bits" }
        var value = 0L
        repeat(len) { value = (value shl 8) or readByte().toLong() }
        return value
    }

    /** WSP integer-value: short-integer or long-integer, whichever the next byte says. */
    fun readIntegerValue(): Long = if (peek() >= 0x80) readShortInteger().toLong() else readLongInteger()

    /**
     * WSP value-length: 0..30 is the length itself; 31 means a uintvar
     * length follows. Returns the byte count of the value that follows.
     */
    fun readValueLength(): Int {
        val b = readByte()
        val length =
            when {
                b <= 30 -> b.toLong()
                b == 31 -> readUintvar()
                else -> throw MalformedPduException("not a value-length")
            }
        require(length <= remaining) { "value-length past end" }
        return length.toInt()
    }

    /**
     * WSP text-string: `[Quote] *TEXT End-of-string` (WAP-230-WSP 8.4.2.1).
     * A leading Quote octet (0x7F, required only when the first TEXT octet
     * is 128..255) is dropped; the NUL terminator is consumed.
     *
     * TEXT is any octet but a CTL (RFC 2616: 0..31 and 127, LWS excepted),
     * and the spec names no charset for it, so the octets are decoded the
     * way the platform's own `PduParser.getWapString` decodes them - CTL
     * octets dropped, everything else kept and read as UTF-8 (identical to
     * US-ASCII for a legal RFC 2616 URI) - so that a Content-Location is
     * handed to the platform as the same string every other SMS app would
     * hand it, rather than with each high octet replaced by U+FFFD.
     */
    fun readTextString(): String {
        if (peek() == 0x7F) skip(1)
        val start = position
        while (readByte() != 0) {
            // advance to the NUL
        }
        val raw = data.copyOfRange(start, position - 1)
        val text = raw.filter { isText(it.toInt() and 0xFF) }.toByteArray()
        return String(text, Charsets.UTF_8)
    }

    /** RFC 2616 TEXT octet: not a CTL, except the LWS characters HT, LF and CR. */
    private fun isText(octet: Int): Boolean = octet in 32..126 || octet >= 128 || octet == 9 || octet == 10 || octet == 13

    /**
     * WSP encoded-string-value: either a plain text-string, or a
     * value-length + charset (integer-value) + text. The charset is applied
     * when it is a known IANA MIBenum; anything else decodes as UTF-8.
     */
    fun readEncodedString(): String {
        val first = peek()
        if (first > 31) return readTextString()
        val length = readValueLength()
        val sub = subReader(length)
        skip(length)
        val charset = charsetFor(sub.readIntegerValue())
        if (sub.peekSafe() == 0x7F) sub.skip(1)
        val start = sub.position
        var endIdx = start
        while (endIdx < sub.end && data[endIdx].toInt() != 0) endIdx++
        return String(data, start, endIdx - start, charset)
    }

    /** A reader bounded to the next [length] bytes; this reader is not advanced. */
    fun subReader(length: Int): WspReader {
        require(length in 0..remaining) { "sub-reader past end" }
        return WspReader(data, end = position + length, start = position)
    }

    /**
     * Skips one WSP field value using the universal first-byte rule:
     * 0..30 = that many bytes follow; 31 = uintvar length then that many
     * bytes; 32..127 = NUL-terminated text; 128..255 = a single octet.
     * This lets the parsers step over any header they do not interpret.
     */
    fun skipFieldValue() {
        when (val b = peek()) {
            in 0..30 -> {
                skip(1)
                skip(b)
            }

            31 -> {
                skip(1)
                val len = readUintvar()
                require(len <= remaining) { "skip past end" }
                skip(len.toInt())
            }

            in 32..127 -> {
                readTextString()
            }

            else -> {
                skip(1)
            }
        }
    }

    private fun peekSafe(): Int = if (hasMore()) peek() else -1

    private inline fun require(
        condition: Boolean,
        message: () -> String,
    ) {
        if (!condition) throw MalformedPduException(message())
    }

    private companion object {
        /** IANA MIBenum -> charset for the values seen in real MMS traffic. */
        fun charsetFor(mibEnum: Long): java.nio.charset.Charset =
            when (mibEnum) {
                3L -> Charsets.US_ASCII
                4L -> Charsets.ISO_8859_1
                else -> Charsets.UTF_8
            }
    }
}
