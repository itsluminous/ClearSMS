package app.clearsms.domain.model

/**
 * User-chosen DISPLAY labels for the Inbox pills (issue #49: "rename pills").
 *
 * A label is presentation only: the pill keeps its [InboxPill] identity, so
 * a renamed pill still filters the same category, the stored pill order and
 * hidden set still refer to it by enum name, message category tags are
 * untouched, and the default-filter preference keeps decoding category
 * names. Removing a pill's entry restores its built-in label.
 *
 * Stored as one `NAME=label` pair per line. [sanitize] guarantees a label
 * never contains a line break, so the encoding needs no escaping; the split
 * is on the FIRST `=`, so labels may contain `=`.
 */
object InboxPillLabels {
    /** Longest label kept; a pill has to fit beside the others in one row. */
    const val MAX_LENGTH = 20

    private const val LINE = "\n"
    private const val SEPARATOR = '='

    /**
     * Normalizes user input: trims, collapses runs of whitespace (including
     * line breaks) to one space and cuts at [MAX_LENGTH]. Null means "no
     * override" - blank input resets the pill to its default label.
     */
    fun sanitize(raw: String): String? {
        val collapsed = raw.trim().replace(Regex("\\s+"), " ")
        if (collapsed.isEmpty()) return null
        return collapsed.take(MAX_LENGTH).trimEnd()
    }

    fun encode(labels: Map<InboxPill, String>): String =
        labels.entries
            .sortedBy { it.key.ordinal }
            .mapNotNull { (pill, label) -> sanitize(label)?.let { "${pill.name}$SEPARATOR$it" } }
            .joinToString(LINE)

    /**
     * Lenient decode, matching the other settings readers: unknown pill
     * names (a pill removed in a later version, or a corrupt line) are
     * dropped, blank labels are dropped, nothing ever throws.
     */
    fun decode(stored: String?): Map<InboxPill, String> {
        if (stored.isNullOrEmpty()) return emptyMap()
        val result = linkedMapOf<InboxPill, String>()
        for (line in stored.split(LINE)) {
            val separator = line.indexOf(SEPARATOR)
            if (separator <= 0) continue
            val pill = InboxPill.entries.firstOrNull { it.name == line.substring(0, separator) } ?: continue
            val label = sanitize(line.substring(separator + 1)) ?: continue
            result.putIfAbsent(pill, label)
        }
        return result
    }
}
