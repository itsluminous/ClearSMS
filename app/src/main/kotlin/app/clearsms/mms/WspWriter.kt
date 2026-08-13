package app.clearsms.mms

import java.io.ByteArrayOutputStream

/**
 * Growable writer for the WSP (Wireless Session Protocol) binary
 * primitives the MMS encapsulation format is built from, as published in
 * the open OMA specifications (OMA-TS-MMS-ENC and WAP-230-WSP) - the
 * encoding mirror of [WspReader]. Only the handful of primitives the
 * app's minimal `m-send-req` encoder needs are implemented.
 */
internal class WspWriter {
    private val out = ByteArrayOutputStream()

    val size: Int get() = out.size()

    fun toByteArray(): ByteArray = out.toByteArray()

    fun writeByte(value: Int) {
        out.write(value and 0xFF)
    }

    fun writeBytes(bytes: ByteArray) {
        out.write(bytes)
    }

    /** WSP uintvar: 7 bits per byte, MSB is the continuation flag. */
    fun writeUintvar(value: Long) {
        require(value >= 0) { "uintvar must be non-negative" }
        var shift = 28
        var started = false
        while (shift > 0) {
            val septet = ((value shr shift) and 0x7F).toInt()
            if (septet != 0 || started) {
                writeByte(septet or 0x80)
                started = true
            }
            shift -= 7
        }
        writeByte((value and 0x7F).toInt())
    }

    /** WSP short-integer: the value's low 7 bits with the high bit set. */
    fun writeShortInteger(value: Int) {
        require(value in 0..0x7F) { "short-integer out of range: $value" }
        writeByte(value or 0x80)
    }

    /** WSP long-integer: short-length followed by big-endian bytes. */
    fun writeLongInteger(value: Long) {
        require(value >= 0) { "long-integer must be non-negative" }
        val bytes = mutableListOf<Int>()
        var v = value
        do {
            bytes.add(0, (v and 0xFF).toInt())
            v = v ushr 8
        } while (v != 0L)
        writeByte(bytes.size)
        bytes.forEach(::writeByte)
    }

    /** WSP integer-value: short-integer when it fits, long-integer otherwise. */
    fun writeIntegerValue(value: Long) {
        if (value in 0..0x7F) writeShortInteger(value.toInt()) else writeLongInteger(value)
    }

    /**
     * WSP value-length: 0..30 as a single byte; 31 + uintvar above that.
     * Callers pass the byte count of the value that follows.
     */
    fun writeValueLength(length: Int) {
        require(length >= 0) { "value-length must be non-negative" }
        if (length <= 30) {
            writeByte(length)
        } else {
            writeByte(31)
            writeUintvar(length.toLong())
        }
    }

    /**
     * WSP text-string: the US-ASCII bytes NUL-terminated; a leading byte
     * >= 0x80 gets the 0x7F quote the spec requires.
     */
    fun writeTextString(value: String) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        if (bytes.isNotEmpty() && (bytes[0].toInt() and 0xFF) >= 0x80) writeByte(0x7F)
        writeBytes(bytes)
        writeByte(0)
    }

    /**
     * WSP quoted-string: an opening quote octet (34), the text, then the
     * end-of-string NUL - the spec form (no closing quote octet).
     */
    fun writeQuotedString(value: String) {
        writeByte(0x22)
        writeBytes(value.toByteArray(Charsets.US_ASCII))
        writeByte(0)
    }
}
