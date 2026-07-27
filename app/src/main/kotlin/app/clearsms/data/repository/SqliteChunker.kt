package app.clearsms.data.repository

/**
 * SQLite binds every element of an `IN (:ids)` list as one host variable and
 * its default variable limit (`SQLITE_MAX_VARIABLE_NUMBER`) is 999. Bulk
 * operations over user-selected id lists must therefore run in chunks, or a
 * "select all" over a large inbox would throw `too many SQL variables`.
 */
internal object SqliteChunker {
    /** Kept comfortably under the 999-variable default. */
    const val MAX_VARIABLES = 900

    /** Splits [items] into ordered chunks of at most [MAX_VARIABLES] elements. */
    fun <T> chunk(items: List<T>): List<List<T>> = if (items.isEmpty()) emptyList() else items.chunked(MAX_VARIABLES)
}
