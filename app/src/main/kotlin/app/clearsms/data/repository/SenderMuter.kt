package app.clearsms.data.repository

import app.clearsms.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * THE single entry point for muting and unmuting a sender - used by the
 * inbox selection overflow, the conversation overflow and the Settings
 * muted-senders dialog, so the three surfaces can never disagree (the same
 * lesson [SenderBlocker] learnt the hard way).
 *
 * Authority is the normalized-sender set in [SettingsRepository.mutedSenders]
 * (covered by the settings backup). Muting changes NOTHING about the
 * sender's messages: they are not binned, not hidden, not marked read;
 * ingestion, categorisation and transaction/OTP extraction run exactly as
 * before. The set is only consulted where a notification is about to post
 * ([app.clearsms.notification.MutedSenderGate]) and where the inbox draws
 * its muted glyph.
 *
 * Interaction with blocking:
 *  - muting a BLOCKED sender is refused ([mute] returns false, writes
 *    nothing): a blocked sender is already binned and silent, so the mute
 *    would be a dead entry the Settings list could never explain;
 *  - blocking a MUTED sender is fine and clears the mute
 *    ([SenderBlocker.block] calls [unmute]) - block supersedes mute, and a
 *    stale "muted" entry for a binned sender would only confuse the list.
 */
@Singleton
class SenderMuter
    @Inject
    constructor(
        private val settings: SettingsRepository,
    ) {
        /**
         * Mutes [sender] (raw or normalized). Returns false - and stores
         * nothing - when the sender is blank after normalization or is
         * currently blocked.
         */
        suspend fun mute(sender: String): Boolean {
            val normalized = SenderNormalizer.normalize(sender)
            if (normalized.isEmpty()) return false
            if (SenderNormalizer.matchesAny(settings.blockedSenders.first(), normalized)) return false
            settings.setMutedSenders(settings.mutedSenders.first() + normalized)
            return true
        }

        /** Unmutes [sender]; removes every stored variant that normalizes to it. */
        suspend fun unmute(sender: String) {
            val current = settings.mutedSenders.first()
            // Same membership rule as isMuted, so a legacy-keyed entry is
            // removable (see SenderBlocker.unblock).
            val remaining = current.filterNot { SenderNormalizer.sameSender(it, sender) }.toSet()
            if (remaining.size != current.size) settings.setMutedSenders(remaining)
        }

        /** Whether [sender] is muted right now. */
        suspend fun isMuted(sender: String): Boolean = SenderNormalizer.matchesAny(settings.mutedSenders.first(), sender)

        /**
         * The single-toggle entry point behind "Mute / Unmute notifications":
         * flips the current state. Returns the NEW muted state, or null when
         * a mute was refused (blocked sender) so the caller can say why.
         */
        suspend fun toggle(sender: String): Boolean? =
            if (isMuted(sender)) {
                unmute(sender)
                false
            } else if (mute(sender)) {
                true
            } else {
                null
            }
    }
