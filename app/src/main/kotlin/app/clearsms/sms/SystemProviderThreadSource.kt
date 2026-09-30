package app.clearsms.sms

import android.content.Context
import android.provider.Telephony
import app.clearsms.data.db.ProviderThreadSource
import app.clearsms.diagnostics.Diag
import app.clearsms.diagnostics.DiagField.Companion.flag

/**
 * [ProviderThreadSource] backed by the system SMS provider: every
 * `content://sms` row's `_id`, `thread_id` and address, for the one-time
 * v21→v22 thread-anchor backfill. Read failures (not the default app,
 * provider unavailable, column omitted) degrade to an empty list - the
 * re-key then runs on sender keys alone rather than failing the migration.
 */
class SystemProviderThreadSource(
    private val context: Context,
) : ProviderThreadSource {
    override fun threads(): List<ProviderThreadSource.ProviderThread> =
        try {
            context.contentResolver
                .query(Telephony.Sms.CONTENT_URI, PROJECTION, null, null, null)
                ?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(Telephony.Sms._ID)
                    val threadIdx = cursor.getColumnIndex(Telephony.Sms.THREAD_ID)
                    val addressIdx = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
                    if (idIdx < 0 || threadIdx < 0 || addressIdx < 0) return@use emptyList()
                    buildList {
                        while (cursor.moveToNext()) {
                            if (cursor.isNull(threadIdx) || cursor.isNull(addressIdx)) continue
                            val threadId = cursor.getLong(threadIdx)
                            if (threadId <= 0L) continue
                            add(
                                ProviderThreadSource.ProviderThread(
                                    smsId = cursor.getLong(idIdx),
                                    threadId = threadId,
                                    address = cursor.getString(addressIdx),
                                ),
                            )
                        }
                    }
                }.orEmpty()
        } catch (e: Exception) {
            Diag.w(TAG, "cannot read provider thread ids; re-key runs on sender keys alone", e, flag("denied", e is SecurityException))
            emptyList()
        }

    private companion object {
        const val TAG = "SystemProviderThreadSource"
        val PROJECTION = arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS)
    }
}
