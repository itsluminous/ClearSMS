package app.clearsms.data.db

import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import app.clearsms.data.repository.SenderNormalizer
import app.clearsms.data.repository.ThreadIdentity
import app.clearsms.diagnostics.Diag
import app.clearsms.diagnostics.DiagField.Companion.count
import app.clearsms.diagnostics.DiagField.Companion.flag

/**
 * Read-only view of the system SMS provider's thread ids, abstracted so the
 * v21→v22 re-key can be driven by a fake in tests. The production
 * implementation is [app.clearsms.sms.SystemProviderThreadSource].
 */
interface ProviderThreadSource {
    /** One `content://sms` row's identity: its `_id`, `thread_id` and address. */
    data class ProviderThread(
        val smsId: Long,
        val threadId: Long,
        val address: String,
    )

    /** Every row's (id, thread, address), or empty when the provider cannot be read. */
    fun threads(): List<ProviderThread>
}

/**
 * v21→v22 (issue #42, one thread per person): the schema change adds
 * `messages.providerThreadId` (nullable, indexed); this spec then
 *
 * 1. **backfills the anchor** from the system provider by `systemSmsId` -
 *    only for one-to-one phone-number rows ([ThreadIdentity.anchorFor]);
 *    rows whose provider row is gone, rows the app never mirrored (MMS,
 *    failed writes) and rows from a restored backup (no `systemSmsId`)
 *    stay unanchored and are keyed by sender alone;
 * 2. **re-keys and merges the threads** in place ([ThreadRekeyer]) under
 *    the region-aware normalizer, so the split conversations users already
 *    have become one - pins, drafts, read / archived state carried over.
 *
 * Runs under [SenderNormalizer.defaultRegion], installed before the
 * database opens (see `DataModule`), so the migration and every later
 * insert agree on the key. Same static-hook pattern as
 * [BackfillMessageDirections]: unset (as in the in-memory test databases)
 * the provider step is skipped and only the re-key runs.
 */
class RekeyThreads : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        val started = System.nanoTime()
        val anchored = backfillAnchors(db)
        val result = ThreadRekeyer.rekey(db)
        Diag.i(
            TAG,
            "threads re-keyed",
            count("rows", result.rows),
            count("anchored", anchored),
            count("rekeyedSenders", result.rekeyedSenders),
            count("mergedThreads", result.mergedThreads),
            count("movedRows", result.movedRows),
            count("ms", (System.nanoTime() - started) / NANOS_PER_MILLI),
            flag("regionKnown", SenderNormalizer.defaultRegion != null),
        )
    }

    private fun backfillAnchors(db: SupportSQLiteDatabase): Int {
        val threads = providerThreadSource?.threads().orEmpty()
        if (threads.isEmpty()) return 0
        db.execSQL("CREATE TEMP TABLE rekey_anchor (smsId INTEGER PRIMARY KEY, threadId INTEGER, address TEXT)")
        try {
            var inserted = 0
            for (row in threads) {
                // The group / non-phone guard is applied on the PROVIDER
                // address, which is the same string the app stored as sender.
                val anchor = ThreadIdentity.anchorFor(row.address, row.threadId) ?: continue
                db.execSQL(
                    "INSERT OR IGNORE INTO rekey_anchor (smsId, threadId, address) VALUES (?, ?, ?)",
                    arrayOf<Any?>(row.smsId, anchor, row.address),
                )
                inserted++
            }
            if (inserted == 0) return 0
            // Joined on id AND address: provider ids are reusable, so an app
            // row whose provider copy was deleted and its id handed to a
            // different sender's message must not inherit that thread.
            db.execSQL(
                """
                UPDATE messages SET providerThreadId =
                    (SELECT a.threadId FROM rekey_anchor a WHERE a.smsId = messages.systemSmsId AND a.address = messages.sender)
                WHERE systemSmsId IN (SELECT smsId FROM rekey_anchor)
                  AND EXISTS (SELECT 1 FROM rekey_anchor a WHERE a.smsId = messages.systemSmsId AND a.address = messages.sender)
                """.trimIndent(),
            )
            return inserted
        } finally {
            db.execSQL("DROP TABLE IF EXISTS rekey_anchor")
        }
    }

    companion object {
        private const val TAG = "RekeyThreads"
        private const val NANOS_PER_MILLI = 1_000_000L

        /** Provider hook, assigned before the production database is built. */
        @Volatile
        var providerThreadSource: ProviderThreadSource? = null
    }
}
