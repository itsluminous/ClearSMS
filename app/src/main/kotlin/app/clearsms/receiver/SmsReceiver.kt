package app.clearsms.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import app.clearsms.data.db.MessageDao
import app.clearsms.data.repository.MessageRepository
import app.clearsms.di.ApplicationScope
import app.clearsms.notification.IncomingMessageRouter
import app.clearsms.sms.TelephonyWriter
import app.clearsms.work.ReminderAlarmScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles `SMS_DELIVER` - the broadcast the platform sends only to the
 * default SMS app for every incoming message.
 *
 * Multipart messages arrive as several PDUs in one intent; parts are merged
 * per sender before ingestion. Each merged message is written to the system
 * SMS provider, run through the categorization pipeline, and then routed to
 * the appropriate notification via [IncomingMessageRouter] (shared with the
 * catch-up import so both paths notify identically).
 */
@AndroidEntryPoint
class SmsReceiver : BroadcastReceiver() {
    @Inject
    lateinit var messageRepository: MessageRepository

    @Inject
    lateinit var messageDao: MessageDao

    @Inject
    lateinit var telephonyWriter: TelephonyWriter

    @Inject
    lateinit var incomingMessageRouter: IncomingMessageRouter

    @Inject
    lateinit var reminderAlarmScheduler: ReminderAlarmScheduler

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = extractParts(intent)
        if (parts.isEmpty()) return
        val subscriptionId = extractSubscriptionId { key, def -> intent.getIntExtra(key, def) }

        val pendingResult = goAsync()
        applicationScope.launch {
            try {
                processIsolating(
                    mergeParts(parts),
                    onError = { _, e ->
                        // Convention: never log message content, OTPs or phone
                        // numbers/sender ids - this line must stay content-free.
                        Log.e(TAG, "Failed to process an incoming message", e)
                    },
                ) { merged -> process(merged, subscriptionId) }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun process(
        merged: Part,
        subscriptionId: Int?,
    ) {
        // Keep the provider row id: without it a later delete commit cannot
        // remove the provider copy, resurrecting the message in other apps.
        // A failed provider write (null) degrades to a Room-only row - the
        // message MUST stay visible in the app either way.
        val systemSmsId =
            telephonyWriter
                .writeInbox(merged.sender, merged.body, merged.timestampMs)
                ?.lastPathSegment
                ?.toLongOrNull()
        val ingest =
            messageRepository.ingestIncoming(merged.sender, merged.body, merged.timestampMs, systemSmsId)
        val entity = ingest.entity
        // Provenance for dual-SIM users: which SIM received the message.
        // Recorded post-ingest (the ingestion contract is subscription-
        // agnostic); a duplicate row keeps the import's NULL - harmless.
        if (subscriptionId != null && !ingest.duplicate) {
            messageDao.setSubscriptionId(entity.id, subscriptionId)
        }
        reminderAlarmScheduler.scheduleForMessage(entity.id)
        // Duplicate = a concurrent catch-up import committed this provider
        // row first. The import sees the row as post-watermark (it just
        // arrived) and notifies it; notifying here too would double-post.
        if (ingest.duplicate) return
        incomingMessageRouter.route(entity)
    }

    /** One decoded PDU (or one merged message). */
    data class Part(
        val sender: String,
        val body: String,
        val timestampMs: Long,
    )

    companion object {
        private const val TAG = "SmsReceiver"

        /**
         * The subscription (SIM) the platform stamped on the `SMS_DELIVER`
         * broadcast, or null when absent/invalid. Both the historical
         * `"subscription"` extra and the documented
         * `SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX` key are consulted -
         * OEM stacks differ in which one they populate. Abstracted over a
         * getter lambda so the precedence rules are testable without a
         * broadcast.
         */
        internal fun extractSubscriptionId(getIntExtra: (key: String, default: Int) -> Int): Int? =
            sequenceOf("subscription", "android.telephony.extra.SUBSCRIPTION_INDEX")
                .map { getIntExtra(it, -1) }
                .firstOrNull { it >= 0 }

        /**
         * Decodes the PDUs from an `SMS_DELIVER` intent. Malformed PDUs are a
         * documented source of runtime exceptions from
         * [Telephony.Sms.Intents.getMessagesFromIntent]; since this runs
         * directly in [onReceive], a throw here would crash the process on
         * every redelivery of the same message, so failures are logged and
         * yield an empty list (no-op) instead.
         */
        internal fun extractParts(intent: Intent): List<Part> {
            val messages =
                try {
                    Telephony.Sms.Intents.getMessagesFromIntent(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Malformed SMS broadcast; dropping", e)
                    null
                }
            return messages.orEmpty().mapNotNull { sms ->
                val sender =
                    try {
                        sms?.displayOriginatingAddress
                    } catch (e: Exception) {
                        Log.e(TAG, "Undecodable SMS PDU; skipping part", e)
                        null
                    } ?: return@mapNotNull null
                Part(sender, sms.displayMessageBody.orEmpty(), sms.timestampMillis)
            }
        }

        /**
         * Runs [process] for each merged message, isolating failures: one
         * message that throws (bad parse, database error, notification
         * failure) is logged via [onError] and must never abort the rest of
         * the batch - or escape and crash the process.
         */
        internal suspend fun processIsolating(
            merged: List<Part>,
            onError: (Part, Exception) -> Unit,
            process: suspend (Part) -> Unit,
        ) {
            for (part in merged) {
                try {
                    process(part)
                } catch (e: Exception) {
                    onError(part, e)
                }
            }
        }

        /**
         * Concatenates multipart segments into whole messages, keeping the
         * earliest timestamp of each group.
         *
         * The platform does not expose the multipart reference number on
         * [android.telephony.SmsMessage], so segments are grouped by sender -
         * but only CONTIGUOUS runs from the same sender are merged. This keeps
         * genuine multipart messages (delivered as adjacent PDUs in one
         * intent) intact while two distinct messages from the same sender that
         * happen to share a broadcast, separated by another sender's part,
         * stay separate rows. Tradeoff: two back-to-back distinct messages
         * from one sender in a single intent are still merged; that is rare
         * and preferable to splitting real multipart messages.
         */
        fun mergeParts(parts: List<Part>): List<Part> {
            val merged = ArrayList<Part>()
            var run = ArrayList<Part>()
            for (part in parts) {
                if (run.isNotEmpty() && run.first().sender != part.sender) {
                    merged += mergeRun(run)
                    run = ArrayList()
                }
                run += part
            }
            if (run.isNotEmpty()) merged += mergeRun(run)
            return merged
        }

        private fun mergeRun(run: List<Part>): Part =
            Part(
                sender = run.first().sender,
                body = run.joinToString(separator = "") { it.body },
                timestampMs = run.minOf { it.timestampMs },
            )
    }
}
