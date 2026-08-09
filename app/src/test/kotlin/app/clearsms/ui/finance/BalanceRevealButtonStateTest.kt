package app.clearsms.ui.finance

import app.clearsms.ui.components.BalanceRevealButton
import app.clearsms.ui.components.RevealButtonState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The in-card reveal button's visibility state machine:
 * the "Show balances" button appears ONLY while balances are hidden
 * (setting OFF and not unlocked); after a successful unlock it becomes the
 * "Hide balances" affordance (the only manual re-mask left on the screen);
 * with the setting ON nothing is masked so no control renders. The
 * (gated, revealed) inputs come from the same state machine exercised in
 * [BalancePrivacyViewModelTest], so auth-cancel/background/setting-change
 * transitions map 1:1 onto button states here.
 */
class BalanceRevealButtonStateTest {
    @Test
    fun `setting ON - no control at all`() {
        assertThat(BalanceRevealButton.state(gated = false, revealed = true)).isEqualTo(RevealButtonState.NONE)
    }

    @Test
    fun `hidden state shows the labelled Show balances button`() {
        assertThat(BalanceRevealButton.state(gated = true, revealed = false)).isEqualTo(RevealButtonState.SHOW_REVEAL)
    }

    @Test
    fun `revealed state becomes the Hide balances affordance`() {
        assertThat(BalanceRevealButton.state(gated = true, revealed = true)).isEqualTo(RevealButtonState.SHOW_HIDE)
    }

    @Test
    fun `auth cancel or failure leaves the Show button in place`() {
        // BalanceUnlock only calls reveal on success; cancel/error never
        // change (gated=true, revealed=false), so the button state is stable.
        val visibility = BalanceVisibility()
        assertThat(BalanceRevealButton.state(gated = true, revealed = visibility.revealed.value))
            .isEqualTo(RevealButtonState.SHOW_REVEAL)
    }

    @Test
    fun `unlock then background re-masks and brings the Show button back`() {
        val visibility = BalanceVisibility()
        visibility.reveal()
        assertThat(BalanceRevealButton.state(gated = true, revealed = visibility.revealed.value))
            .isEqualTo(RevealButtonState.SHOW_HIDE)
        // MainActivity.onStop → conceal() when the app leaves the foreground.
        visibility.conceal()
        assertThat(BalanceRevealButton.state(gated = true, revealed = visibility.revealed.value))
            .isEqualTo(RevealButtonState.SHOW_REVEAL)
    }

    @Test
    fun `toggling the setting ON removes the control even after an unlock`() {
        val visibility = BalanceVisibility()
        visibility.reveal()
        // SettingsViewModel.setShowBalance conceals before every write, then
        // gated flips to false - the control disappears entirely.
        visibility.conceal()
        assertThat(BalanceRevealButton.state(gated = false, revealed = visibility.revealed.value))
            .isEqualTo(RevealButtonState.NONE)
    }
}
