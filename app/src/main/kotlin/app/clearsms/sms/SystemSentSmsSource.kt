package app.clearsms.sms

import android.content.Context
import android.provider.Telephony
import android.util.Log
import app.clearsms.data.db.SentSmsSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SentSmsSource] backed by the system SMS provider: every
 * `type = MESSAGE_TYPE_SENT` row of `content://sms`, with its delivery
 * status. Read failures (permission not yet granted, provider unavailable)
 * degrade to an empty list - the direction backfill then leaves every row
 * incoming rather than crashing the migration.
 */
@Singleton
class SystemSentSmsSource
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SentSmsSource {
        override fun sentMessages(): List<SentSmsSource.SentSms> =
            try {
                context.contentResolver
                    .query(
                        Telephony.Sms.CONTENT_URI,
                        PROJECTION,
                        "${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_SENT}",
                        null,
                        "${Telephony.Sms._ID} ASC",
                    )?.use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                add(
                                    SentSmsSource.SentSms(
                                        id = cursor.getLong(0),
                                        address = cursor.getString(1) ?: continue,
                                        body = cursor.getString(2) ?: continue,
                                        dateMs = cursor.getLong(3),
                                        delivered = !cursor.isNull(4) && cursor.getInt(4) == Telephony.Sms.STATUS_COMPLETE,
                                    ),
                                )
                            }
                        }
                    }.orEmpty()
            } catch (e: Exception) {
                Log.w(TAG, "Cannot read the sent box; direction backfill will keep rows incoming", e)
                emptyList()
            }

        private companion object {
            const val TAG = "SystemSentSmsSource"

            val PROJECTION =
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.STATUS,
                )
        }
    }
