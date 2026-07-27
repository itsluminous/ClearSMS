package app.clearsms.ui.conversation

import android.content.Context
import android.provider.Telephony
import app.clearsms.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the outcome of a dispatched SMS by watching its persisted system
 * provider row. [app.clearsms.sms.SmsSender] writes the outgoing message to
 * the sent box before dispatch, and [app.clearsms.receiver.SmsSentReceiver]
 * flips that row to `MESSAGE_TYPE_FAILED` when the radio reports a failure —
 * so the row's `type` column is the persisted message status this watcher
 * polls until [SendOutcome] resolves.
 */
@Singleton
class SentMessageWatcher
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        /**
         * Suspends until the send resolves: [SendStatus.FAILED] as soon as a
         * failure is recorded, otherwise [SendStatus.SENT] once
         * [SendOutcome.RESULT_WINDOW_MS] passes without one.
         */
        suspend fun await(
            destination: String,
            body: String,
            dispatchedAtMs: Long,
        ): SendStatus =
            withContext(ioDispatcher) {
                val startedAt = System.currentTimeMillis()
                var status = resolveNow(destination, body, dispatchedAtMs, startedAt)
                while (status == SendStatus.SENDING) {
                    delay(SendOutcome.POLL_INTERVAL_MS)
                    status = resolveNow(destination, body, dispatchedAtMs, startedAt)
                }
                status
            }

        private fun resolveNow(
            destination: String,
            body: String,
            dispatchedAtMs: Long,
            startedAtMs: Long,
        ): SendStatus =
            SendOutcome.resolve(
                providerType = latestRowType(destination, body, dispatchedAtMs),
                elapsedMs = System.currentTimeMillis() - startedAtMs,
            )

        /**
         * `type` of the newest provider row matching this send, or null when
         * no row is observable (e.g. not the default SMS app, so nothing was
         * written).
         */
        private fun latestRowType(
            destination: String,
            body: String,
            dispatchedAtMs: Long,
        ): Int? =
            try {
                context.contentResolver
                    .query(
                        Telephony.Sms.CONTENT_URI,
                        arrayOf(Telephony.Sms.TYPE),
                        "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.BODY} = ? AND ${Telephony.Sms.DATE} >= ?",
                        arrayOf(destination, body, (dispatchedAtMs - DATE_SLACK_MS).toString()),
                        "${Telephony.Sms.DATE} DESC",
                    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else null }
            } catch (_: Exception) {
                null
            }

        private companion object {
            /** Tolerance between our dispatch clock and the provider row's date. */
            const val DATE_SLACK_MS = 2_000L
        }
    }
