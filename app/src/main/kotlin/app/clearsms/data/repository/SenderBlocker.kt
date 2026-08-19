package app.clearsms.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.di.ApplicationScope
import app.clearsms.di.UiSettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * THE single entry point for blocking and unblocking a sender - used by
 * both the inbox selection bar and the Settings block-list dialog, so the
 * two surfaces can never disagree again (historically the inbox action
 * wrote only a per-row flag, leaving the sender invisible to - and
 * un-unblockable from - the Settings dialog).
 *
 * Authority is the normalized-sender set in
 * [SettingsRepository.blockedSenders] (covered by the settings backup).
 * Every [block] additionally:
 * - updates the derived per-row `isBlockedSender` cache, and
 * - moves the sender's existing conversation to the recycle bin (or drops
 *   it when the bin is off) - the "blocked threads disappear" effect.
 *
 * Blocking deliberately offers NO undo snackbar: an undo would have to
 * atomically unblock AND restore, which the single-pending-action
 * [UndoManager] cannot express - and both halves are already reversible by
 * hand (Settings unblocks, the recycle bin restores).
 *
 * [unblock] stops future binning but deliberately does NOT restore
 * previously binned messages: bin restore is a manual, message-level
 * decision the user already has (Recycle bin -> Restore), and silently
 * resurrecting a whole thread on unblock would surprise more than it helps.
 */
@Singleton
class SenderBlocker
    @Inject
    constructor(
        private val settings: SettingsRepository,
        private val repository: MessageRepository,
        @UiSettingsDataStore private val uiSettingsDataStore: DataStore<Preferences>,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        suspend fun block(sender: String) {
            val normalized = SenderNormalizer.normalize(sender)
            if (normalized.isEmpty()) return
            settings.setBlockedSenders(settings.blockedSenders.first() + normalized)
            repository.setBlocked(normalized, blocked = true)
            repository.binThreadForSender(normalized)
        }

        suspend fun unblock(sender: String) {
            val normalized = SenderNormalizer.normalize(sender)
            // Remove every entry that normalizes to the target, so a legacy
            // raw entry ("VM-JIOPAY") goes when the user unblocks "JIOPAY".
            settings.setBlockedSenders(
                settings.blockedSenders
                    .first()
                    .filterNot { SenderNormalizer.normalize(it) == normalized }
                    .toSet(),
            )
            repository.setBlocked(normalized, blocked = false)
        }

        /**
         * One-time-ish legacy reconcile, run on every app start (idempotent):
         * - entries in the old ui_settings `blocked_senders` key (where the
         *   Settings dialog stored its mirror before the set moved next to
         *   `blocked_keywords`) are folded in normalized, then cleared;
         * - per-row `isBlockedSender` flags (the ONLY record the pre-unified
         *   inbox block action left) are folded in, making those blocks
         *   finally visible in - and unblockable from - the dialog.
         */
        fun onAppStart() {
            scope.launch { reconcileLegacy() }
        }

        internal suspend fun reconcileLegacy() {
            val legacyUiEntries = uiSettingsDataStore.data.first()[LEGACY_UI_BLOCKED_SENDERS] ?: emptySet()
            val flagged = repository.legacyBlockedSenderFlags()
            val additions = (legacyUiEntries + flagged).mapTo(HashSet()) { SenderNormalizer.normalize(it) } - ""
            if (additions.isNotEmpty()) {
                settings.setBlockedSenders(settings.blockedSenders.first() + additions)
            }
            if (legacyUiEntries.isNotEmpty()) {
                uiSettingsDataStore.edit { it.remove(LEGACY_UI_BLOCKED_SENDERS) }
            }
        }

        private companion object {
            /** The pre-unification Settings-dialog mirror in the ui_settings store. */
            val LEGACY_UI_BLOCKED_SENDERS = stringSetPreferencesKey("blocked_senders")
        }
    }
