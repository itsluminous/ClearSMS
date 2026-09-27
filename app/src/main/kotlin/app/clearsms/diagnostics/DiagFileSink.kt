package app.clearsms.diagnostics

import java.io.File

/**
 * Two-file rotating store for [DiagLine]s so a report can include what
 * happened BEFORE the current process started (a crash is recorded, the
 * process dies, and the next launch still has the trace).
 *
 * Layout: `diag.0.log` is the active file; when an append would push it past
 * [maxFileBytes] it becomes `diag.1.log` (replacing the previous one) and a
 * fresh active file starts. Two files of 128 KiB each bound disk use at
 * 256 KiB, matching the in-memory budget. File format: each entry starts with
 * a line `<epochMs>\t<first text line>`; continuation lines (stack frames)
 * begin with a space and belong to the preceding entry.
 *
 * Not thread-safe by itself: [Diag] serialises appends on one writer thread.
 */
class DiagFileSink(
    private val dir: File,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
) {
    private val active = File(dir, "diag.0.log")
    private val previous = File(dir, "diag.1.log")

    /** Oldest-first lines from both files; unparseable content is skipped. */
    fun restore(): List<DiagLine> {
        val lines = ArrayList<DiagLine>()
        for (file in listOf(previous, active)) {
            if (!file.isFile) continue
            var current: DiagLine? = null
            file.forEachLine { raw ->
                val head = ENTRY_HEAD.matchEntire(raw)
                if (head != null) {
                    current?.let(lines::add)
                    current = DiagLine(head.groupValues[1].toLong(), head.groupValues[2])
                } else if (raw.startsWith(" ") && current != null) {
                    current = current!!.copy(text = current!!.text + "\n" + raw)
                }
            }
            current?.let(lines::add)
        }
        return lines
    }

    /** Appends [line], rotating first when the active file would overflow. */
    fun append(line: DiagLine) {
        dir.mkdirs()
        val encoded = encode(line)
        if (active.isFile && active.length() + encoded.length > maxFileBytes) rotate()
        active.appendText(encoded)
    }

    /** Deletes both files (used by tests and to reclaim space). */
    fun clear() {
        active.delete()
        previous.delete()
    }

    private fun rotate() {
        previous.delete()
        if (active.isFile && !active.renameTo(previous)) {
            active.copyTo(previous, overwrite = true)
            active.delete()
        }
    }

    private fun encode(line: DiagLine): String {
        val parts = line.text.split('\n')
        return buildString {
            append(line.timestampMs).append('\t').append(parts.first()).append('\n')
            // Continuation lines are indented in DiagLine.render; keep them
            // recognisable even if a frame line came in flush-left.
            parts.drop(1).forEach { append(if (it.startsWith(" ")) it else " $it").append('\n') }
        }
    }

    companion object {
        const val DEFAULT_MAX_FILE_BYTES = 128L * 1024
        private val ENTRY_HEAD = Regex("^(\\d{1,16})\\t(.*)$")
    }
}
