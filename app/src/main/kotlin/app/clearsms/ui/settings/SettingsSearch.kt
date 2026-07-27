package app.clearsms.ui.settings

/**
 * Client-side filter over the declarative settings row list.
 *
 * A blank [query] returns every row (clearing the search restores the full
 * list). Otherwise a row matches when any whitespace-separated keyword of
 * the query appears, case-insensitively, in its title or summary. Rows keep
 * their original order, so grouping by section stays stable.
 */
fun <T> filterSettingsRows(
    rows: List<T>,
    query: String,
    title: (T) -> String,
    summary: (T) -> String,
): List<T> {
    val keywords = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (keywords.isEmpty()) return rows
    return rows.filter { row ->
        val haystack = "${title(row)} ${summary(row)}"
        keywords.all { keyword -> haystack.contains(keyword, ignoreCase = true) }
    }
}
