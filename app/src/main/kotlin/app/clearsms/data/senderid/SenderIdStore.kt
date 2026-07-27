package app.clearsms.data.senderid

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import android.util.LruCache
import app.clearsms.domain.categorizer.SenderIdLookup
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SenderInfo
import java.io.File
import java.io.FileOutputStream

/**
 * Read-only directory of known SMS sender IDs.
 *
 * Backed by a plain SQLite file (`sender_ids.db`, NOT Room) that is copied from the
 * app assets to internal storage on first access. Lookups normalize the sender ID by
 * uppercasing and stripping TRAI route prefixes (`XY-`) and suffixes (`-S`, `-P`,
 * `-T`, `-G`); the raw uppercased ID is tried as well. Results (including misses)
 * are cached in a small LRU cache.
 */
class SenderIdStore(
    private val context: Context,
) : SenderIdLookup {
    private val lock = Any()
    private var database: SQLiteDatabase? = null
    private var openFailed = false

    /** Caches lookups; misses are cached as [MISS] to avoid repeated queries. */
    private val cache = LruCache<String, Any>(CACHE_SIZE)

    override fun lookup(senderId: String): SenderInfo? {
        val raw = senderId.trim().uppercase()
        if (raw.isEmpty()) return null
        cache.get(raw)?.let { cached ->
            return cached as? SenderInfo
        }
        val db = openDatabase() ?: return null
        val result = queryCandidates(db, candidatesFor(raw))
        cache.put(raw, result ?: MISS)
        return result
    }

    /** Candidate keys tried in order: stripped form first, then the raw ID. */
    private fun candidatesFor(raw: String): List<String> {
        var stripped = raw
        // TRAI headers look like "VM-HDFCBK-S": a 2-letter route prefix and a
        // single-letter content-type suffix around the registered 6-char sender ID.
        stripped = stripped.replace(PREFIX_REGEX, "")
        stripped = stripped.replace(SUFFIX_REGEX, "")
        return if (stripped != raw && stripped.isNotEmpty()) listOf(stripped, raw) else listOf(raw)
    }

    private fun queryCandidates(
        db: SQLiteDatabase,
        candidates: List<String>,
    ): SenderInfo? {
        for (candidate in candidates) {
            db
                .rawQuery(
                    "SELECT name, category, sub FROM sender_ids WHERE sender_id = ? LIMIT 1",
                    arrayOf(candidate),
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0)
                        val category =
                            when (cursor.getString(1).lowercase()) {
                                "important" -> Category.IMPORTANT
                                else -> Category.PROMOTIONAL
                            }
                        val sub = if (cursor.isNull(2)) null else cursor.getString(2)
                        return SenderInfo(name = name, category = category, sub = sub)
                    }
                }
        }
        return null
    }

    private fun openDatabase(): SQLiteDatabase? {
        synchronized(lock) {
            database?.let { return it }
            if (openFailed) return null
            return try {
                val file = ensureLocalCopy()
                SQLiteDatabase
                    .openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                    .also { database = it }
            } catch (e: Exception) {
                // The asset may be absent in stripped builds; degrade to "no directory".
                Log.w(TAG, "Sender ID database unavailable", e)
                openFailed = true
                null
            }
        }
    }

    /** Copies the asset to filesDir once, using a tmp file + rename for atomicity. */
    private fun ensureLocalCopy(): File {
        val target = File(context.filesDir, DB_FILE_NAME)
        if (target.exists()) return target
        val tmp = File(context.filesDir, "$DB_FILE_NAME.tmp")
        context.assets.open(ASSET_NAME).use { input ->
            FileOutputStream(tmp).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            error("Could not move sender ID database into place")
        }
        return target
    }

    private companion object {
        const val TAG = "SenderIdStore"
        const val ASSET_NAME = "sender_ids.db"
        const val DB_FILE_NAME = "sender_ids.db"
        const val CACHE_SIZE = 512
        val PREFIX_REGEX = Regex("^[A-Z]{2}-")
        val SUFFIX_REGEX = Regex("-[SPTG]$")

        /** Sentinel cached for senders that are not in the directory. */
        val MISS = Any()
    }
}
