package app.clearsms.notification

import android.content.Context
import android.os.Build
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.di.ApplicationScope
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.NotificationAction
import app.clearsms.domain.model.SubCategory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single notification-routing decision for an incoming message: OTP,
 * scam warning, parsed transaction/balance/bill, plain message, promotion,
 * or silence - respecting every user-facing gate (blocked senders, the
 * transaction-notification toggle, OTP auto-copy, selected actions).
 *
 * Extracted from [app.clearsms.receiver.SmsReceiver] so the catch-up import
 * ([app.clearsms.work.InitialSyncWorker] via [CatchUpNotifier]) can notify
 * recent caught-up messages through EXACTLY the pipeline live deliveries
 * use - same channels, same ids, so read-cancellation and dedup keep
 * working no matter which path posted the notification.
 */
@Singleton
class IncomingMessageRouter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settingsRepository: SettingsRepository,
        private val otpNotifier: OtpNotifier,
        private val messageNotifier: MessageNotifier,
        private val transactionNotifier: TransactionNotifier,
        @ApplicationScope private val applicationScope: CoroutineScope,
    ) {
        /** Routes [entity] to its notification (or to silence). */
        suspend fun route(entity: MessageEntity) {
            if (entity.isBlockedSender) return
            val selectedActions = settingsRepository.notificationActions.first()
            when {
                entity.category == Category.OTP && entity.extractedOtp != null -> notifyOtp(entity, selectedActions)
                entity.subCategory == SubCategory.SCAM -> messageNotifier.notifyScam(entity)
                // Parsed transaction/balance/bill notification (opt-out via
                // settings). Balance-only updates (BANK_ALERT with a parsed
                // balance) and bill reminders (BILL with a parsed amount due)
                // ride the SAME transactionNotifications gate as transactions:
                // they are one parsed-finance surface rendered by one notifier
                // with one semantic color scheme, and a second toggle would add
                // a confusing third state for the same notification style. When
                // the setting is off - or the message has no renderable parsed
                // data (notify returns false) - control falls through to the
                // plain message notification below, i.e. today's behavior.
                (
                    entity.subCategory == SubCategory.TRANSACTION ||
                        entity.subCategory == SubCategory.BANK_ALERT ||
                        entity.subCategory == SubCategory.BILL
                ) &&
                    settingsRepository.transactionNotifications.first() &&
                    transactionNotifier.notify(entity, selectedActions) -> Unit
                entity.category == Category.PERSONAL || entity.category == Category.IMPORTANT ->
                    messageNotifier.notify(entity, selectedActions)
                // Promotions always post to their own "Promotions" channel, which
                // is created BLOCKED (IMPORTANCE_NONE) - so nothing is shown until
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
    }
