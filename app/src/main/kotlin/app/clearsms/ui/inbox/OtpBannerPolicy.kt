package app.clearsms.ui.inbox

import app.clearsms.data.db.MessageEntity
import app.clearsms.ui.navigation.Routes

/**
 * Decides which OTP (if any) the inbox banner shows, and where tapping it
 * navigates. Pure so the whole behavior is unit-testable on the JVM.
 */
object OtpBannerPolicy {
    /**
     * Maximum age of an OTP surfaced in the banner. One-time passwords
     * typically expire within 5–10 minutes, so anything older is stale noise
     * on launch: 10 minutes keeps every still-usable code visible without
     * ever resurrecting a dead one.
     */
    const val MAX_AGE_MS = 10L * 60 * 1000

    /**
     * Picks the newest still-fresh OTP the user has not handled.
     *
     * [handledMessageId] is the persisted id of the last OTP copied or
     * dismissed (see
     * [app.clearsms.data.prefs.SettingsRepository.handledOtpMessageId]).
     * Because message ids grow monotonically, hiding every id at or below it
     * keeps a handled OTP gone across navigation, app restarts and
     * re-categorization (ids survive recategorize), while a newer OTP -
     * which always gets a higher id - still appears.
     */
    fun select(
        messages: List<MessageEntity>,
        handledMessageId: Long,
        nowMs: Long,
    ): MessageEntity? =
        messages
            .asSequence()
            .filter { it.extractedOtp != null }
            .filter { it.timestamp >= nowMs - MAX_AGE_MS }
            .filter { it.id > handledMessageId }
            .maxByOrNull { it.timestamp }

    /**
     * Route for a banner tap: the source conversation, carrying the message
     * id so the existing open-and-highlight behavior fires on the OTP.
     */
    fun navigationRoute(otp: LatestOtp): String = Routes.conversation(otp.threadId, otp.messageId)
}
