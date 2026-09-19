package app.clearsms.sms

import android.content.Context
import android.provider.Telephony
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only view of the system SMS provider reduced to what the one-time
 * SIM backfill needs: id, identity fields (body + timestamp) and the
 * `sub_id` column. Abstracted so the backfill is unit-testable without a
 * content provider - the same seam pattern as [SentSmsSource].
 */
interface ProviderSimSource {
    /** One provider row, with its subscription (null = missing/invalid). */
    data class ProviderSim(
        val id: Long,
        val body: String?,
        val dateMs: Long,
        val subscriptionId: Int?,
    )

    /** The next [limit] rows with `_id` greater than [afterId], ascending. */
    fun page(
        afterId: Long,
        limit: Int,
    ): List<ProviderSim>
}

/**
 * [ProviderSimSource] backed by `content://sms`. Defensive the same way
 * [SystemSmsImporter] is: some providers ignore the projection, so column
 * indices are guarded, and a missing column, NULL cell or invalid value
 * (`SubscriptionManager.INVALID_SUBSCRIPTION_ID` / -1) reads as null -
 * UNKNOWN, never a guessed SIM. Read failures degrade to an empty page,
 * which simply ends the backfill run (a later run retries).
 */
@Singleton
class SystemProviderSimSource
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ProviderSimSource {
        override fun page(
            afterId: Long,
            limit: Int,
        ): List<ProviderSimSource.ProviderSim> =
            try {
                context.contentResolver
                    .query(
                        Telephony.Sms.CONTENT_URI,
                        PROJECTION,
                        "${Telephony.Sms._ID} > ?",
                        arrayOf(afterId.toString()),
                        "${Telephony.Sms._ID} ASC LIMIT $limit",
                    )?.use { cursor ->
                        val idIdx = cursor.getColumnIndex(Telephony.Sms._ID)
                        val bodyIdx = cursor.getColumnIndex(Telephony.Sms.BODY)
                        val dateIdx = cursor.getColumnIndex(Telephony.Sms.DATE)
                        val subIdx = cursor.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)
                        if (idIdx < 0) return@use emptyList()
                        buildList {
                            while (cursor.moveToNext()) {
                                add(
                                    ProviderSimSource.ProviderSim(
                                        id = cursor.getLong(idIdx),
                                        body = if (bodyIdx >= 0) cursor.getString(bodyIdx) else null,
                                        dateMs = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L,
                                        subscriptionId =
                                            if (subIdx >= 0 && !cursor.isNull(subIdx)) {
                                                cursor.getInt(subIdx).takeIf { it >= 0 }
                                            } else {
                                                null
                                            },
                                    ),
                                )
                            }
                        }
                    }.orEmpty()
            } catch (e: Exception) {
                Log.w(TAG, "Cannot read the system SMS provider; SIM backfill will retry later", e)
                emptyList()
            }

        private companion object {
            const val TAG = "SystemProviderSimSource"

            val PROJECTION =
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.SUBSCRIPTION_ID,
                )
        }
    }
