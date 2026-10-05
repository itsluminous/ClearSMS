package app.clearsms.notification

import android.content.Context
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.di.ApplicationScope
import app.clearsms.diagnostics.Diag
import app.clearsms.diagnostics.DiagField.Companion.flag
import app.clearsms.diagnostics.DiagField.Companion.id
import app.clearsms.diagnostics.DiagField.Companion.label
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
 * spam, unknown-sender notification, or silence - respecting every user-facing
 * gate (blocked senders, muted senders, the
 * transaction-notification toggle, OTP auto-copy, selected actions).
 *
 * The MUTE decision is made here, once, before any notifier is chosen -
 * this is the source every incoming-message notification flows from, so a
 * muted sender cannot slip through a branch that forgot to check. The
 * per-message notifiers consult [MutedSenderGate] again before posting
 * (NotificationSectionConventionTest pins that), so a future caller that
 * reaches a notifier around this router is caught too. What a mute keeps
 * and drops (OTP dropped, scam warning kept) is argued on [MutedSenderGate].
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
        private val mutedSenderGate: MutedSenderGate,
        @ApplicationScope private val applicationScope: CoroutineScope,
    ) {
        /** Routes [entity] to its notification (or to silence). */
        suspend fun route(entity: MessageEntity) {
            val muted = mutedSenderGate.isMuted(entity.sender)
            // The routing inputs, so a "no notification arrived" report shows
            // which gate below silenced the message. Never the OTP itself.
            Diag.d(
                TAG,
                "routing",
                id("message", entity.id),
                label("category", entity.category),
                label("sub", entity.subCategory),
                flag("otp", entity.extractedOtp != null),
                flag("blocked", entity.isBlockedSender),
                flag("muted", muted),
                flag("deleted", entity.deletedAt != null),
            )
            if (entity.isBlockedSender) return
            // Born-deleted rows (keyword-blocked at ingest) are silent: no
            // OTP, transaction, scam or message notification may exist for a
            // message that was never inbox-visible.
            if (entity.deletedAt != null) return
            // Muted sender: the ONE notification that survives is the scam
            // warning - muting is not accepting the risk that the sender
            // starts phishing. Everything else, the OTP included, stays quiet
            // (the code is still extracted and copyable in-app). Decided
            // before the type switch so no branch below can forget it.
            if (muted) {
                if (entity.subCategory == SubCategory.SCAM) messageNotifier.notifyScam(entity)
                return
            }
            val selectedActions = settingsRepository.notificationActions.first()
            when {
                entity.category == Category.OTP && entity.extractedOtp != null -> {
                    notifyOtp(entity, selectedActions)
                }

                entity.subCategory == SubCategory.SCAM -> {
                    messageNotifier.notifyScam(entity)
                }

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
                    transactionNotifier.notify(entity, selectedActions) -> {
                    Unit
                }

                entity.category == Category.PERSONAL || entity.category == Category.IMPORTANT -> {
                    messageNotifier.notify(entity, selectedActions)
                }

                // Promotions always post to their own "Promotions" channel, which
                // is created BLOCKED (IMPORTANCE_NONE) - so nothing is shown until
                // the user enables the category in Android's notification settings.
                // Posting unconditionally is what makes that switch meaningful: an
                // extra in-app gate would silently swallow them and the Android
                // toggle would appear to do nothing.
                entity.category == Category.PROMOTIONAL -> {
                    messageNotifier.notify(entity, selectedActions, channelId = Channels.PROMOTIONS)
                }

                // Spam: same shape as promotions, its own blocked channel
                // (Channels.SPAM). Ordered AFTER the scam branch above on
                // purpose - a message that is both sorted as spam and
                // FLAGGED as a scam keeps its security warning; only
                // unflagged spam lands here. MessageNotifier applies the
                // Inbox section gate like every other message notification.
                entity.category == Category.SPAM -> {
                    messageNotifier.notify(entity, selectedActions, channelId = Channels.SPAM)
                }

                // Unknown senders get the same per-thread message notification
                // on their own ENABLED channel (Channels.UNKNOWN) - a real
                // person texting from a non-contact number must not arrive
                // silently. Reusing MessageNotifier keeps the thread id band,
                // deep-link highlight and read-in-app cancellation identical
                // to plain messages.
                entity.category == Category.UNKNOWN -> {
                    messageNotifier.notify(entity, selectedActions, channelId = Channels.UNKNOWN)
                }

                // The only reachable remainder: an OTP-category message whose
                // code could not be extracted. Deliberately silent - a bare
                // "OTP" notification with nothing to copy would be noise.
                else -> {
                    Unit
                }
            }
        }

        private suspend fun notifyOtp(
            entity: MessageEntity,
            selectedActions: Set<NotificationAction>,
        ) {
            val otp = entity.extractedOtp ?: return
            val autoCopy = settingsRepository.otpAutoCopy.first()
            // Auto-copy runs on every Android version. Writing to the
            // clipboard from the background is explicitly permitted:
            // AOSP's ClipboardService gates OP_WRITE_CLIPBOARD with
            // "Writing is allowed without focus", and only
            // OP_READ_CLIPBOARD is restricted to the focused app or the
            // default IME from Android 10. An earlier gate here
            // (SDK_INT < Q) read the platform's "limited access to
            // clipboard data" note as covering writes too, which silently
            // disabled this setting - on by default - for every user above
            // Android 9 (issue #95). The copy stays best-effort: a ROM that
            // does block the write leaves the notification's Copy action and
            // the conversation's Copy OTP button, exactly as before.
            if (autoCopy) {
                OtpClipboard.copy(context, otp, applicationScope)
            }
            otpNotifier.notify(entity, otp, settingsRepository.otpDisplaySize.first(), selectedActions)
        }

        private companion object {
            const val TAG = "Router"
        }
    }
