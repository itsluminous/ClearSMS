package app.clearsms.sms

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import app.clearsms.data.repository.SqliteChunker
import app.clearsms.data.repository.SystemSmsDeleter
import app.clearsms.data.repository.SystemSmsReadWriter
import app.clearsms.data.repository.SystemSmsReinserter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes messages into the system SMS provider (`content://sms`).
 *
 * A default SMS app is responsible for persisting incoming and outgoing
 * messages to the platform provider so other apps (and a future default app)
 * can see them. All writes are no-ops when Clear SMS is not the default app,
 * because only the default app may write to the provider.
 */
@Singleton
class TelephonyWriter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SystemSmsDeleter,
        SystemSmsReadWriter,
        SystemSmsReinserter {
        /** Inserts a received message into the inbox. Returns the row uri, or null. */
        fun writeInbox(
            sender: String,
            body: String,
            timestampMs: Long,
        ): Uri? =
            insert(
                Telephony.Sms.Inbox.CONTENT_URI,
                ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, sender)
                    put(Telephony.Sms.BODY, body)
                    put(Telephony.Sms.DATE, timestampMs)
                    put(Telephony.Sms.READ, 0)
                    put(Telephony.Sms.SEEN, 0)
                },
            )

        /** Inserts an outgoing message into the sent box. Returns the row uri, or null. */
        fun writeSent(
            destination: String,
            body: String,
            timestampMs: Long,
        ): Uri? =
            insert(
                Telephony.Sms.Sent.CONTENT_URI,
                ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, destination)
                    put(Telephony.Sms.BODY, body)
                    put(Telephony.Sms.DATE, timestampMs)
                    put(Telephony.Sms.READ, 1)
                    put(Telephony.Sms.SEEN, 1)
                },
            )

        /**
         * Re-inserts a restored incoming message (recycle-bin restore) into
         * the provider inbox, preserving its read state. Returns the fresh
         * row id, or null when not the default app / on failure — the
         * in-app restore proceeds regardless.
         */
        override fun reinsertInbox(
            sender: String,
            body: String,
            timestampMs: Long,
            read: Boolean,
        ): Long? =
            insert(
                Telephony.Sms.Inbox.CONTENT_URI,
                ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, sender)
                    put(Telephony.Sms.BODY, body)
                    put(Telephony.Sms.DATE, timestampMs)
                    put(Telephony.Sms.READ, if (read) 1 else 0)
                    // Restored history is never "new": no notification badge.
                    put(Telephony.Sms.SEEN, 1)
                },
            )?.rowId()

        /** Re-inserts a restored outgoing message into the provider sent box. */
        override fun reinsertSent(
            destination: String,
            body: String,
            timestampMs: Long,
        ): Long? = writeSent(destination, body, timestampMs)?.rowId()

        /** Row id from a provider insert uri (`content://sms/<id>`), or null. */
        private fun Uri.rowId(): Long? = lastPathSegment?.toLongOrNull()

        /** Marks a previously written outgoing message as failed. */
        fun markFailed(messageUri: Uri) {
            update(
                messageUri,
                ContentValues().apply {
                    put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED)
                },
            )
        }

        /** Records a successful delivery report against an outgoing message. */
        fun markDelivered(messageUri: Uri) {
            update(
                messageUri,
                ContentValues().apply {
                    put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_COMPLETE)
                },
            )
        }

        /**
         * Deletes provider rows by `_id`, in chunks below SQLite's variable
         * limit. No-ops when Clear SMS is not the default app (only the
         * default app may delete), and degrades to a logged warning on
         * [SecurityException] — the local Room deletion has already
         * succeeded, so the user-visible operation still completes.
         */
        override fun deleteBySystemIds(systemIds: List<Long>): Int {
            if (systemIds.isEmpty()) return 0
            if (!DefaultSmsAppHelper.isDefaultSmsApp(context)) return 0
            var deleted = 0
            for (chunk in SqliteChunker.chunk(systemIds)) {
                deleted +=
                    try {
                        val placeholders = chunk.joinToString(",") { "?" }
                        context.contentResolver.delete(
                            Telephony.Sms.CONTENT_URI,
                            "${Telephony.Sms._ID} IN ($placeholders)",
                            chunk.map(Long::toString).toTypedArray(),
                        )
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Not allowed to delete from the system SMS provider", e)
                        0
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to delete from the system SMS provider", e)
                        0
                    }
            }
            return deleted
        }

        /**
         * Propagates read-state to the system SMS provider's `read` column by
         * `_id`, in chunks below SQLite's variable limit. This is what makes a
         * "mark read" survive reinstalls and stay in sync with other SMS apps:
         * the provider is the shared source of truth, and the importer seeds
         * each message's read-state from it. No-ops when Clear SMS is not the
         * default app, and degrades to a logged warning on failure (the local
         * Room update has already succeeded).
         */
        override fun setReadBySystemIds(
            systemIds: List<Long>,
            read: Boolean,
        ) {
            if (systemIds.isEmpty()) return
            if (!DefaultSmsAppHelper.isDefaultSmsApp(context)) return
            val values = ContentValues().apply { put(Telephony.Sms.READ, if (read) 1 else 0) }
            for (chunk in SqliteChunker.chunk(systemIds)) {
                try {
                    val placeholders = chunk.joinToString(",") { "?" }
                    context.contentResolver.update(
                        Telephony.Sms.CONTENT_URI,
                        values,
                        "${Telephony.Sms._ID} IN ($placeholders)",
                        chunk.map(Long::toString).toTypedArray(),
                    )
                } catch (e: SecurityException) {
                    Log.w(TAG, "Not allowed to update read-state in the system SMS provider", e)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to update read-state in the system SMS provider", e)
                }
            }
        }

        private fun insert(
            uri: Uri,
            values: ContentValues,
        ): Uri? {
            if (!DefaultSmsAppHelper.isDefaultSmsApp(context)) return null
            return try {
                context.contentResolver.insert(uri, values)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write to the system SMS provider", e)
                null
            }
        }

        private fun update(
            uri: Uri,
            values: ContentValues,
        ) {
            if (!DefaultSmsAppHelper.isDefaultSmsApp(context)) return
            try {
                context.contentResolver.update(uri, values, null, null)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update the system SMS provider", e)
            }
        }

        private companion object {
            const val TAG = "TelephonyWriter"
        }
    }
