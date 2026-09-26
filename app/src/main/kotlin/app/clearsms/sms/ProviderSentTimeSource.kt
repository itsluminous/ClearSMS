package app.clearsms.sms

import android.content.Context
import android.provider.Telephony
import android.util.Log
import app.clearsms.domain.model.sentTimestampOrNull
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only view of the system SMS provider reduced to what the one-time
 * sent-time backfill needs: id, identity fields (body + received date) and
 * the `date_sent` column of INBOX rows. Same seam pattern as
 * [ProviderSimSource] so the backfill is unit-testable without a content
 * provider.
 */
interface ProviderSentTimeSource {
    /** One inbox row, with its sender timestamp (null = not reported). */
    data class ProviderSentTime(
        val id: Long,
        val body: String?,
        val dateMs: Long,
        val dateSentMs: Long?,
    )

    /** The next [limit] inbox rows with `_id` greater than [afterId], ascending. */
    fun page(
        afterId: Long,
        limit: Int,
    ): List<ProviderSentTime>
}

/**
 * [ProviderSentTimeSource] backed by `content://sms`. Defensive the same
 * way [SystemProviderSimSource] is: column indices are guarded, a missing
 * column or NULL cell reads as null, and the provider's 0 ("the network
 * reported no sent time") reads as null too - UNKNOWN, never an epoch
 * timestamp. Only INBOX rows are read: an outgoing row's send time is its
 * own date. Read failures degrade to an empty page, which ends the run (a
 * later run retries from the checkpoint).
 */
@Singleton
class SystemProviderSentTimeSource
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ProviderSentTimeSource {
        override fun page(
            afterId: Long,
            limit: Int,
        ): List<ProviderSentTimeSource.ProviderSentTime> =
            try {
                context.contentResolver
                    .query(
                        Telephony.Sms.CONTENT_URI,
                        PROJECTION,
                        SELECTION,
                        arrayOf(afterId.toString()),
                        "${Telephony.Sms._ID} ASC LIMIT $limit",
                    )?.use { cursor ->
                        val idIdx = cursor.getColumnIndex(Telephony.Sms._ID)
                        val bodyIdx = cursor.getColumnIndex(Telephony.Sms.BODY)
                        val dateIdx = cursor.getColumnIndex(Telephony.Sms.DATE)
                        val sentIdx = cursor.getColumnIndex(Telephony.Sms.DATE_SENT)
                        if (idIdx < 0) return@use emptyList()
                        buildList {
                            while (cursor.moveToNext()) {
                                add(
                                    ProviderSentTimeSource.ProviderSentTime(
                                        id = cursor.getLong(idIdx),
                                        body = if (bodyIdx >= 0) cursor.getString(bodyIdx) else null,
                                        dateMs = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L,
                                        dateSentMs =
                                            if (sentIdx >= 0 && !cursor.isNull(sentIdx)) {
                                                sentTimestampOrNull(cursor.getLong(sentIdx))
                                            } else {
                                                null
                                            },
                                    ),
                                )
                            }
                        }
                    }.orEmpty()
            } catch (e: Exception) {
                Log.w(TAG, "Cannot read the system SMS provider; sent-time backfill will retry later", e)
                emptyList()
            }

        private companion object {
            const val TAG = "SystemProviderSentTimeSource"

            const val SELECTION = "${Telephony.Sms._ID} > ? AND ${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_INBOX}"

            val PROJECTION =
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.DATE_SENT,
                )
        }
    }
