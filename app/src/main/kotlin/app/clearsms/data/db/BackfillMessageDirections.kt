package app.clearsms.data.db

import android.util.Log
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Read-only view of the system SMS provider's sent box, abstracted so the
 * v6→v7 backfill can be driven by a fake in tests. The production
 * implementation is [app.clearsms.sms.SystemSentSmsSource].
 */
interface SentSmsSource {
    /** One `type = MESSAGE_TYPE_SENT` row from `content://sms`. */
    data class SentSms(
        val id: Long,
        val address: String,
        val body: String,
        val dateMs: Long,
        /** True when the provider row carries `STATUS_COMPLETE` (a delivery report arrived). */
        val delivered: Boolean,
    )

    /** All sent messages, or empty when the provider cannot be read. */
    fun sentMessages(): List<SentSms>
}

/**
 * v6→v7 backfill: the schema change adds `messages.isOutgoing` (DEFAULT 0)
 * and `messages.deliveryStatus`, but every pre-upgrade row - including
 * messages the user sent - defaults to incoming, which would render the
 * whole history left-aligned. The system SMS provider knows the truth
 * (`type` = 2 marks sent rows), so this reconciles against it:
 *
 * 1. **By stored provider id** - imported rows carry `systemSmsId`; any row
 *    whose id appears in the provider's sent box becomes outgoing.
 * 2. **By exact sender + timestamp + body** - replies sent from the app
 *    before this release were persisted without `systemSmsId`, but with the
 *    same address, body and millisecond timestamp as their provider row.
 *
 * Matched rows get `deliveryStatus` SENT, or DELIVERED when the provider row
 * carries a completed delivery report. Rows matching neither pass - and
 * everything when the provider is unreadable - keep the column defaults and
 * stay incoming: the safe, explicit fallback.
 *
 * Room instantiates this spec itself; the provider hook is the static
 * [sentSmsSource] (assigned before the production database is built) rather
 * than a `@ProvidedAutoMigrationSpec` constructor parameter, because a
 * provided spec must be handed to EVERY database build - including the many
 * in-memory builders across the test suite that never migrate. Unset (as in
 * those tests) the backfill is a no-op.
 */
class BackfillMessageDirections : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        val sent = sentSmsSource?.sentMessages().orEmpty()
        if (sent.isEmpty()) {
            Log.i(TAG, "No sent provider rows to reconcile; all rows stay incoming")
            return
        }
        db.execSQL(
            "CREATE TEMP TABLE backfill_sent " +
                "(id INTEGER, address TEXT, body TEXT, date INTEGER, delivered INTEGER)",
        )
        try {
            for (row in sent) {
                db.execSQL(
                    "INSERT INTO backfill_sent (id, address, body, date, delivered) VALUES (?, ?, ?, ?, ?)",
                    arrayOf(row.id, row.address, row.body, row.dateMs, if (row.delivered) 1 else 0),
                )
            }
            db.execSQL(
                """
                UPDATE messages SET
                    isOutgoing = 1,
                    deliveryStatus = (
                        SELECT CASE WHEN s.delivered = 1 THEN 'DELIVERED' ELSE 'SENT' END
                        FROM backfill_sent s WHERE s.id = messages.systemSmsId LIMIT 1
                    )
                WHERE systemSmsId IN (SELECT id FROM backfill_sent)
                """.trimIndent(),
            )
            db.execSQL(
                """
                UPDATE messages SET
                    isOutgoing = 1,
                    deliveryStatus = (
                        SELECT CASE WHEN s.delivered = 1 THEN 'DELIVERED' ELSE 'SENT' END
                        FROM backfill_sent s
                        WHERE s.address = messages.sender
                          AND s.body = messages.body
                          AND s.date = messages.timestamp
                        LIMIT 1
                    )
                WHERE isOutgoing = 0 AND systemSmsId IS NULL
                  AND EXISTS (
                      SELECT 1 FROM backfill_sent s
                      WHERE s.address = messages.sender
                        AND s.body = messages.body
                        AND s.date = messages.timestamp
                  )
                """.trimIndent(),
            )
            val matched = countMatched(db)
            Log.i(
                TAG,
                "Reconciled ${sent.size} provider sent rows: $matched messages marked " +
                    "outgoing; unmatched rows default to incoming",
            )
        } finally {
            db.execSQL("DROP TABLE IF EXISTS backfill_sent")
        }
    }

    private fun countMatched(db: SupportSQLiteDatabase): Long =
        db.query("SELECT COUNT(*) FROM messages WHERE isOutgoing = 1").use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }

    companion object {
        private const val TAG = "BackfillDirections"

        /**
         * Where the migration reads the sent box from. Assigned by the DI
         * layer before the production database opens; null (tests that never
         * hit the v6→v7 migration) leaves every row incoming.
         */
        @Volatile
        var sentSmsSource: SentSmsSource? = null
    }
}
