package app.clearsms.diagnostics

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Assembles the text a user shares: the [SystemState] header, then the
 * buffered log lines inside the chosen [ReportWindow], optionally with
 * sender ids masked, capped at [MAX_BYTES]. The SAME string is what the
 * preview shows and what goes into the zip - the preview is the contract.
 */
object DiagnosticReport {
    /** Hard cap on the shared text; the newest lines win, oldest are dropped. */
    const val MAX_BYTES = 384 * 1024

    const val ZIP_MIME = "application/zip"

    /**
     * Shared stem of the zip and the text inside it, e.g.
     * `clearsms-diagnostics-2026-09-28-024351Z`. Timestamped so two
     * reports never overwrite each other in a mail client or download
     * folder and can be told apart at a glance; to the second so two in one
     * minute differ too. UTC with a literal `Z`, matching the log lines in
     * the body (also UTC) so a maintainer can line the name up with them
     * without a conversion; the header's `timeZone:` gives the user's local
     * zone. Only `[a-z0-9-]` and `Z`: no colons, spaces or `+`, so it is
     * safe on every filesystem and in every mail client.
     */
    fun fileStem(nowMs: Long): String = "clearsms-diagnostics-" + STEM_TIME.format(Instant.ofEpochMilli(nowMs))

    /** Name of the text entry inside the zip. */
    fun textName(nowMs: Long): String = fileStem(nowMs) + ".txt"

    /** Name of the shared zip. */
    fun zipName(nowMs: Long): String = fileStem(nowMs) + ".zip"

    fun build(
        header: SystemState,
        lines: List<DiagLine>,
        window: ReportWindow,
        maskSenders: Boolean,
        nowMs: Long,
    ): String {
        val since = window.minutes?.let { nowMs - it * 60_000L }
        val selected = if (since == null) lines else lines.filter { it.timestampMs >= since }
        val masked = if (maskSenders) SenderMasker().mask(selected.map { it.text }) else selected.map { it.text }
        val head =
            buildString {
                appendLine("Clear SMS diagnostic report")
                appendLine("window: ${window.label}")
                appendLine("senderIds: ${if (maskSenders) "masked" else "as recorded"}")
                appendLine("entries: ${masked.size}")
                append(header.render())
                appendLine("----")
            }
        return cap(head, masked, MAX_BYTES)
    }

    /**
     * Keeps [head] intact and as many of the NEWEST [bodyLines] as fit in
     * [maxBytes]; a marker line records how many older lines were dropped.
     */
    internal fun cap(
        head: String,
        bodyLines: List<String>,
        maxBytes: Int,
    ): String {
        var budget = maxBytes - head.toByteArray().size - DROP_MARKER_RESERVE
        val kept = ArrayList<String>()
        for (line in bodyLines.asReversed()) {
            val cost = line.toByteArray().size + 1
            if (cost > budget) break
            budget -= cost
            kept += line
        }
        kept.reverse()
        val dropped = bodyLines.size - kept.size
        return buildString {
            append(head)
            if (dropped > 0) appendLine("[$dropped older entries dropped to fit the ${maxBytes / 1024} KiB size cap]")
            kept.forEach { appendLine(it) }
        }
    }

    /** Writes [text] as [textName] (at [nowMs]) inside a zip on [out]. */
    fun writeZip(
        text: String,
        out: OutputStream,
        nowMs: Long,
    ) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(textName(nowMs)))
            zip.write(text.toByteArray())
            zip.closeEntry()
        }
    }

    /** [writeZip] into memory - the size the share sheet will carry. */
    fun zipBytes(
        text: String,
        nowMs: Long,
    ): ByteArray = ByteArrayOutputStream().also { writeZip(text, it, nowMs) }.toByteArray()

    private const val DROP_MARKER_RESERVE = 80

    private val STEM_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss'Z'").withZone(ZoneOffset.UTC)
}

/** How far back a report reaches. The user picks one on the diagnostics screen. */
enum class ReportWindow(
    val minutes: Int?,
    val label: String,
) {
    LAST_5_MINUTES(5, "last 5 minutes"),
    LAST_15_MINUTES(15, "last 15 minutes"),
    LAST_HOUR(60, "last 60 minutes"),
    EVERYTHING(null, "everything recorded"),
    ;

    companion object {
        /** Long enough to cover "it just happened" without drowning a reader. */
        val DEFAULT = LAST_15_MINUTES
    }
}

/**
 * Replaces every `sender=<id>` value with a numbered placeholder. The
 * mapping is assigned in order of first appearance and lives only for one
 * report, so the same sender reads consistently within it ("sender#2 was
 * categorised by rule X, then sender#2 again") while nothing in the output
 * allows recovering the original id. The [DiagField.PHONE] and
 * [DiagField.DROPPED] placeholders are already content-free and pass
 * through untouched.
 */
class SenderMasker {
    private val placeholders = LinkedHashMap<String, String>()

    fun mask(lines: List<String>): List<String> = lines.map(::maskLine)

    fun maskLine(line: String): String =
        SENDER_FIELD.replace(line) { match ->
            val original = match.value
            if (original.startsWith("[")) {
                original
            } else {
                placeholders.getOrPut(original) { "sender#${placeholders.size + 1}" }
            }
        }

    companion object {
        private val SENDER_FIELD = Regex("(?<=\\bsender=)\\S+")
    }
}
