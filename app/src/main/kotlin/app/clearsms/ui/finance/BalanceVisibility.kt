package app.clearsms.ui.finance

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session-scoped reveal state for the "Show balance" privacy gate.
 *
 * The reveal lifetime rule, in full:
 * - starts concealed on every process start (nothing is persisted);
 * - [reveal] is called only after a successful device-lock authentication;
 * - a reveal lasts until the app leaves the foreground
 *   ([app.clearsms.MainActivity.onStop], configuration changes excepted),
 *   until the user taps the eye to hide again, or until the "Show balance"
 *   setting is written (either direction) - whichever comes first.
 *
 * Cancelled or failed authentication never calls [reveal], so balances stay
 * masked. Holding the flag in memory only means nothing sensitive is ever
 * stored as a result of this feature.
 */
@Singleton
class BalanceVisibility
    @Inject
    constructor() {
        private val revealedFlow = MutableStateFlow(false)

        /** True after a successful device-lock auth, until the next [conceal]. */
        val revealed: StateFlow<Boolean> = revealedFlow

        fun reveal() {
            revealedFlow.value = true
        }

        fun conceal() {
            revealedFlow.value = false
        }
    }
