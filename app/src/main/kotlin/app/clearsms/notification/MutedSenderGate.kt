package app.clearsms.notification

import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.data.repository.SenderNormalizer
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The mute gate: whether a sender the user MUTED may notify right now.
 *
 * Mute is the middle ground between normal delivery and blocking. A muted
 * sender's messages still arrive, still land in the inbox (unread, badged,
 * searchable), still extract transactions, bills and OTPs - they just make
 * no sound and post no notification. Blocking, by contrast, bins the
 * message before anything sees it (see [IncomingMessageRouter]).
 *
 * Consulted in two places, on purpose:
 *  - [IncomingMessageRouter], THE source of every incoming-message
 *    notification, decides once per message and short-circuits early
 *    (this is also where the decision is logged for "why no notification"
 *    reports);
 *  - every per-message notifier ([OtpNotifier], [MessageNotifier],
 *    [TransactionNotifier]) checks again before posting, exactly like the
 *    [NotificationSectionGate] - so a future caller that reaches a notifier
 *    around the router cannot forget the mute. NotificationSectionConventionTest
 *    pins that every notifier taking a MessageEntity references this gate.
 *
 * What a mute suppresses, and the two deliberate decisions:
 *  - plain, promotional, unknown-sender and spam notifications, the parsed
 *    transaction/balance/bill notification: SUPPRESSED - this is what the
 *    user asked for;
 *  - the OTP notification: SUPPRESSED. "Mute means quiet" is the only
 *    predictable contract; a mute that still pinged for OTPs would teach the
 *    user that mute is unreliable. The OTP is still extracted, still shown
 *    in the inbox banner and the conversation with its Copy button. The
 *    mute entry points say so ("OTPs included");
 *  - the scam warning ([app.clearsms.domain.model.SubCategory.SCAM]): KEPT.
 *    Muting a sender is not accepting the risk that its route starts
 *    phishing, and a muted sender is exactly the one whose messages the
 *    user is no longer glancing at. [MessageNotifier.notifyScam] therefore
 *    does not consult this gate, and the router routes a muted scam
 *    message to it and nothing else.
 *
 * Matching reuses the blocked-sender normalisation
 * ([SenderNormalizer.matchesAny]): TRAI route prefixes/suffixes fall away
 * ("VM-HDFCBK-S" matches an entry "HDFCBK") and phone numbers compare by
 * their region-aware national key ("+91 98765 43210" matches "9876543210"),
 * with an entry stored under the pre-#42 ten-digit key still matching. Entries
 * are stored normalized but re-normalized here, so a raw variant from a
 * restored backup still matches. Read at DECISION time (a `first()`), so a
 * mute racing a delivery errs on the side of the freshest choice.
 *
 * Mutes are permanent until unmuted: a timed mute ("8 hours") needs a
 * scheduler, an expiry store and an "until" label everywhere the muted
 * glyph shows, and the request was for senders that should never notify.
 */
@Singleton
class MutedSenderGate
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
    ) {
        /** Whether [sender] (raw or normalized) is currently muted. */
        suspend fun isMuted(sender: String): Boolean = matches(settingsRepository.mutedSenders.first(), sender)

        /** Whether a notification about a message from [sender] may post right now. */
        suspend fun allows(sender: String): Boolean = !isMuted(sender)

        companion object {
            /** Pure matching rule ([SenderNormalizer.matchesAny]), shared with the inbox's muted-glyph lookup. */
            fun matches(
                mutedSenders: Set<String>,
                sender: String,
            ): Boolean = SenderNormalizer.matchesAny(mutedSenders, sender)
        }
    }
