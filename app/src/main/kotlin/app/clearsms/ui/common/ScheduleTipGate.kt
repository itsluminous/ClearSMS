package app.clearsms.ui.common

import app.clearsms.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gate for the one-time "long-press Send to schedule" tip: long-press is
 * invisible, so the FIRST send from any compose bar (per install, DataStore
 * flag) earns a snackbar pointing at it. The mutex makes the check-and-set
 * atomic - two racing sends can never both claim the first slot.
 */
@Singleton
class ScheduleTipGate
    @Inject
    constructor(
        private val settings: SettingsRepository,
    ) {
        private val mutex = Mutex()

        /**
         * True exactly once per install: the first call flips the persisted
         * flag and asks for the tip; every later call declines.
         */
        suspend fun shouldShowTip(): Boolean =
            mutex.withLock {
                if (settings.scheduleSendTipShown.first()) {
                    false
                } else {
                    settings.setScheduleSendTipShown(true)
                    true
                }
            }

        /**
         * Consumes the tip without showing it - a user who just SCHEDULED a
         * message plainly knows about long-press already.
         */
        suspend fun markShown() {
            mutex.withLock { settings.setScheduleSendTipShown(true) }
        }
    }
