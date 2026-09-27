package app.clearsms.diagnostics

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Severity of a diagnostic entry; the single letter is what the report shows. */
enum class DiagLevel(
    val letter: Char,
) {
    DEBUG('D'),
    INFO('I'),
    WARN('W'),
    ERROR('E'),
}

/**
 * One rendered log line as the buffer and the rotating file hold it: the
 * instant it was recorded plus its already-formatted text. Entries are
 * rendered ONCE, at record time, so the in-memory copy, the on-disk copy and
 * the shared report are byte-identical and nothing sensitive can be
 * re-derived later from a richer structure.
 */
data class DiagLine(
    val timestampMs: Long,
    val text: String,
) {
    /** Approximate footprint used for the byte bound (UTF-8 upper estimate + newline). */
    val bytes: Int get() = text.length + 1

    companion object {
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)

        /**
         * Renders a structured entry. Layout:
         * `2026-09-27 07:07:21.937Z I SmsReceiver ingested sender=HDFCBK message=42`
         * followed, for an error, by indented class-chain and frame lines.
         * Exception MESSAGES are deliberately not rendered: platform and
         * SQLite messages can echo bound values, so only class names and
         * stack frames survive.
         */
        fun render(
            timestampMs: Long,
            level: DiagLevel,
            tag: String,
            event: String,
            fields: List<DiagField>,
            error: Throwable?,
        ): DiagLine {
            val text =
                buildString {
                    append(TIME.format(Instant.ofEpochMilli(timestampMs))).append("Z ")
                    append(level.letter).append(' ')
                    append(sanitizeToken(tag)).append(' ')
                    append(sanitizeEvent(event))
                    fields.forEach { append(' ').append(sanitizeToken(it.name)).append('=').append(it.value) }
                    if (error != null) appendError(this, error)
                }
            return DiagLine(timestampMs, text)
        }

        private fun appendError(
            out: StringBuilder,
            error: Throwable,
        ) {
            var cause: Throwable? = error
            var depth = 0
            val seen = HashSet<Throwable>()
            while (cause != null && depth < MAX_CAUSES && seen.add(cause)) {
                out.append('\n').append(if (depth == 0) "  ! " else "  caused by ").append(cause.javaClass.name)
                cause.stackTrace.take(MAX_FRAMES).forEach { frame ->
                    out
                        .append("\n      at ")
                        .append(frame.className)
                        .append('.')
                        .append(frame.methodName)
                        .append('(')
                        .append(frame.fileName ?: "Unknown")
                        .append(':')
                        .append(frame.lineNumber)
                        .append(')')
                }
                cause = cause.cause
                depth++
            }
        }

        // Tags and events are developer-written constants; the sanitisers
        // only keep the line format parseable (no newlines, no '=' in names).
        private fun sanitizeToken(value: String): String = value.replace(Regex("[\\s=]"), "_")

        private fun sanitizeEvent(value: String): String = value.replace('\n', ' ').replace('\r', ' ')

        private const val MAX_FRAMES = 12
        private const val MAX_CAUSES = 4
    }
}

/**
 * Bounded, thread-safe ring of [DiagLine]s: capped by entry COUNT and by
 * total BYTES, evicting the oldest first. Every mutation is synchronized on
 * the buffer; readers take a snapshot copy.
 */
class DiagBuffer(
    val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    val maxBytes: Int = DEFAULT_MAX_BYTES,
) {
    private val lines = ArrayDeque<DiagLine>()
    private var bytes = 0

    /** Appends [line], evicting oldest entries until both bounds hold. */
    fun append(line: DiagLine) {
        synchronized(this) {
            lines.addLast(line)
            bytes += line.bytes
            while (lines.size > maxEntries || (bytes > maxBytes && lines.size > 1)) {
                bytes -= lines.removeFirst().bytes
            }
            // A single line larger than the whole budget is kept truncated
            // rather than making the buffer permanently empty.
            if (bytes > maxBytes) {
                val only = lines.removeFirst()
                val trimmed = only.copy(text = only.text.take(maxBytes - 1))
                lines.addLast(trimmed)
                bytes = trimmed.bytes
            }
        }
    }

    /** Oldest-first copy of the lines at or after [sinceMs] (all when null). */
    fun snapshot(sinceMs: Long? = null): List<DiagLine> =
        synchronized(this) {
            if (sinceMs == null) lines.toList() else lines.filter { it.timestampMs >= sinceMs }
        }

    val size: Int get() = synchronized(this) { lines.size }

    val byteCount: Int get() = synchronized(this) { bytes }

    companion object {
        /** In-memory bounds: 2 000 entries / 256 KiB - about an hour of busy ingest. */
        const val DEFAULT_MAX_ENTRIES = 2_000
        const val DEFAULT_MAX_BYTES = 256 * 1024
    }
}
