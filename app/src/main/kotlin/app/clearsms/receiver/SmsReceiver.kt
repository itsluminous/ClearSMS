package app.clearsms.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.util.Log
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.data.repository.MessageRepository
import app.clearsms.di.ApplicationScope
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.NotificationAction
import app.clearsms.domain.model.SubCategory
import app.clearsms.notification.Channels
import app.clearsms.notification.MessageNotifier
import app.clearsms.notification.OtpClipboard
import app.clearsms.notification.OtpNotifier
import app.clearsms.notification.TransactionNotifier
import app.clearsms.sms.TelephonyWriter
import app.clearsms.work.ReminderAlarmScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles `SMS_DELIVER` — the broadcast the platform sends only to the
 * default SMS app for every incoming message.
 *
 * Multipart messages arrive as several PDUs in one intent; parts are merged
 * per sender before ingestion. Each merged message is written to the system
 * SMS provider, run through the categorization pipeline, and then routed to
 * the appropriate notification.
 */
@AndroidEntryPoint
class SmsReceiver : BroadcastReceiver() {
    @Inject
    lateinit var messageRepository: MessageRepository

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var telephonyWriter: TelephonyWriter

    @Inject
    lateinit var otpNotifier: OtpNotifier

    @Inject
    lateinit var messageNotifier: MessageNotifier

    @Inject
    lateinit var transactionNotifier: TransactionNotifier

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

        val pendingResult = goAsync()
        applicationScope.launch {
            try {
                processIsolating(
                    mergeParts(parts),
                    onError = { _, e ->
                        // Convention: never log message content, OTPs or phone
                        // numbers/sender ids — this line must stay content-free.
                        Log.e(TAG, "Failed to process an incoming message", e)
                    },
                ) { merged -> process(context, merged) }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun process(
        context: Context,
        merged: Part,
    ) {
        telephonyWriter.writeInbox(merged.sender, merged.body, merged.timestampMs)
        val entity = messageRepository.insertIncoming(merged.sender, merged.body, merged.timestampMs)
        reminderAlarmScheduler.scheduleForMessage(entity.id)
        if (entity.isBlockedSender) return

        val selectedActions = settingsRepository.notificationActions.first()
        when {
            entity.category == Category.OTP && entity.extractedOtp != null -> notifyOtp(context, entity, selectedActions)
            entity.subCategory == SubCategory.SCAM -> messageNotifier.notifyScam(entity)
            // Parsed transaction/balance notification (opt-out via settings).
            // Balance-only updates (BANK_ALERT with a parsed balance) ride
            // the SAME transactionNotifications gate as transactions: they
            // are one parsed-finance surface, and a second toggle would add
            // a confusing third state for the same notification style. When
            // the setting is off — or the message has no renderable parsed
            // data (notify returns false) — control falls through to the
            // plain message notification below, i.e. today's behavior.
            (entity.subCategory == SubCategory.TRANSACTION || entity.subCategory == SubCategory.BANK_ALERT) &&
                settingsRepository.transactionNotifications.first() &&
                transactionNotifier.notify(entity, selectedActions) -> Unit
            entity.category == Category.PERSONAL || entity.category == Category.IMPORTANT ->
                messageNotifier.notify(entity, selectedActions)
            // Promotions always post to their own "Promotions" channel, which
            // is created BLOCKED (IMPORTANCE_NONE) — so nothing is shown until
            // the user enables the category in Android's notification settings.
            // Posting unconditionally is what makes that switch meaningful: an
            // extra in-app gate would silently swallow them and the Android
            // toggle would appear to do nothing.
            entity.category == Category.PROMOTIONAL ->
                messageNotifier.notify(entity, selectedActions, channelId = Channels.PROMOTIONS)
            // Everything else (unknown, informational) stays silent by design.
            else -> Unit
        }
    }

    private suspend fun notifyOtp(
        context: Context,
        entity: MessageEntity,
        selectedActions: Set<NotificationAction>,
    ) {
        val otp = entity.extractedOtp ?: return
        val autoCopy = settingsRepository.otpAutoCopy.first()
        // Auto-copy: before Android Q a background component may write to the
        // clipboard directly. From Q onward background clipboard access is
        // restricted, so auto-copy is honored through the notification's
        // "Copy" action instead (a user-triggered foreground path).
        if (autoCopy && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            OtpClipboard.copy(context, otp, applicationScope)
        }
        otpNotifier.notify(entity, otp, settingsRepository.otpDisplaySize.first(), selectedActions)
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
         * the batch — or escape and crash the process.
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
         * [android.telephony.SmsMessage], so segments are grouped by sender —
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
