package app.clearsms.ui.inbox

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Visibility state for the "Clear SMS is not your default SMS app" inbox
 * banner.
 *
 * The role is re-checked on every ON_RESUME (see the wiring in
 * [InboxScreen]), so returning from the system role dialog updates the
 * banner live. Dismissal is session-scoped only: the instance lives in a
 * `remember { }` in the inbox composition, so the banner reappears on the
 * next launch while the role is still missing — losing the default-SMS role
 * means new messages silently stop arriving, which must not stay hidden
 * forever.
 *
 * Kept free of Android role APIs so the held/dismissed → visible mapping is
 * unit-testable on the JVM.
 */
class DefaultSmsBannerState {
    /** Result of the most recent role check; assume held until checked. */
    var roleHeld: Boolean by mutableStateOf(true)
        private set

    /** Whether the user dismissed the banner this session. */
    var dismissed: Boolean by mutableStateOf(false)
        private set

    /** The banner shows while the role is missing and not yet dismissed. */
    val visible: Boolean get() = !roleHeld && !dismissed

    /**
     * Records a role check (launch, resume, or role-dialog result). Gaining
     * the role clears any dismissal, so a later loss shows the banner again.
     */
    fun onRoleChecked(held: Boolean) {
        roleHeld = held
        if (held) dismissed = false
    }

    /** Hides the banner for the rest of this session. */
    fun dismiss() {
        dismissed = true
    }
}
