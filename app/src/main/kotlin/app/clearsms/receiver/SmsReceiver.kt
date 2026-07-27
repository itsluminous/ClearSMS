package app.clearsms.receiver

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import app.clearsms.R
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.data.repository.MessageRepository
import app.clearsms.di.ApplicationScope
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SubCategory
import app.clearsms.notification.MessageNotifier
import app.clearsms.notification.OtpNotifier
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
    lateinit var reminderAlarmScheduler: ReminderAlarmScheduler

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts =
            Telephony.Sms.Intents.getMessagesFromIntent(intent).orEmpty().mapNotNull { sms ->
                val sender = sms?.displayOriginatingAddress ?: return@mapNotNull null
                Part(sender, sms.displayMessageBody.orEmpty(), sms.timestampMillis)
            }
        if (parts.isEmpty()) return

        val pendingResult = goAsync()
        applicationScope.launch {
            try {
                for (merged in mergeParts(parts)) {
                    process(context, merged)
                }
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

        when {
            entity.category == Category.OTP && entity.extractedOtp != null -> notifyOtp(context, entity)
            entity.subCategory == SubCategory.SCAM -> messageNotifier.notifyScam(entity)
            entity.category == Category.PERSONAL || entity.category == Category.IMPORTANT ->
                messageNotifier.notify(entity)
            // Promotional and unknown messages stay silent by design.
            else -> Unit
        }
    }

    private suspend fun notifyOtp(
        context: Context,
        entity: MessageEntity,
    ) {
        val otp = entity.extractedOtp ?: return
        val autoCopy = settingsRepository.otpAutoCopy.first()
        // Auto-copy: before Android Q a background component may write to the
        // clipboard directly. From Q onward background clipboard access is
        // restricted, so auto-copy is honored through the notification's
        // "Copy" action instead (a user-triggered foreground path).
        if (autoCopy && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.otp_clip_label), otp))
        }
        otpNotifier.notify(entity, otp, settingsRepository.otpDisplaySize.first())
    }

    /** One decoded PDU (or one merged message). */
    data class Part(
        val sender: String,
        val body: String,
        val timestampMs: Long,
    )

    companion object {
        /**
         * Concatenates multipart segments per sender, preserving arrival
         * order, and keeps the earliest timestamp of each group.
         */
        fun mergeParts(parts: List<Part>): List<Part> =
            parts
                .groupBy { it.sender }
                .map { (sender, group) ->
                    Part(
                        sender = sender,
                        body = group.joinToString(separator = "") { it.body },
                        timestampMs = group.minOf { it.timestampMs },
                    )
                }
    }
}
