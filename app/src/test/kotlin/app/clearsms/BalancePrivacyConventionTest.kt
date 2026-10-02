package app.clearsms

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Source-level invariants for the balance privacy gate and the (previously
 * dead) transaction-details setting, in the same spirit as
 * [SensitiveLoggingConventionTest]:
 *
 * 1. `SettingsViewModel.setShowBalance` must drop the session reveal before
 *    every write, so toggling the setting off re-masks immediately and a
 *    stale unlock never survives an OFF→ON→OFF cycle.
 * 2. `MainActivity` must re-mask when the app leaves the foreground
 *    (`onStop`), while sparing configuration changes.
 * 3. `showTransactionDetails` must actually be consumed by the conversation
 *    UI - it shipped write-only once; this pins the fix.
 * 4. The reveal control must expose state-dependent labels, and the masked
 *    value must stay out of the accessibility tree.
 */
class BalancePrivacyConventionTest {
    private fun source(path: String): String {
        val file = File("src/main/kotlin/app/clearsms/$path")
        assertWithMessage("expected source file $path").that(file.isFile).isTrue()
        return file.readText()
    }

    @Test
    fun `setShowBalance conceals the session reveal before writing`() {
        val body =
            source("ui/settings/SettingsViewModel.kt")
                .substringAfter("fun setShowBalance")
                .substringBefore("fun ")
        assertWithMessage("setShowBalance must call balanceVisibility.conceal()")
            .that(body)
            .contains("balanceVisibility.conceal()")
        assertWithMessage("setShowBalance must persist the setting")
            .that(body)
            .contains("settings.setShowBalance(value)")
    }

    @Test
    fun `MainActivity re-masks on background but not on rotation`() {
        val text = source("MainActivity.kt")
        assertWithMessage("MainActivity must conceal balances in onStop")
            .that(text)
            .contains("override fun onStop")
        assertWithMessage("conceal must be skipped for configuration changes")
            .that(text)
            .contains("if (!isChangingConfigurations) balanceVisibility.conceal()")
    }

    @Test
    fun `showTransactionDetails is consumed, not just written`() {
        assertWithMessage("ConversationViewModel must expose the setting")
            .that(source("ui/conversation/ConversationViewModel.kt"))
            .contains("settings.showTransactionDetails")
        val screen = source("ui/conversation/ConversationScreen.kt")
        assertWithMessage("ConversationScreen must gate the parsed card on it")
            .that(screen)
            .contains("DetailCardVisibility.shouldShow")
        assertWithMessage("the state field must reach the bubble")
            .that(screen)
            .contains("state.showTransactionDetails")
    }

    @Test
    fun `reveal control has state-dependent labels and the masked value hides from TalkBack`() {
        val button = source("ui/components/BalanceRevealButton.kt")
        assertWithMessage("hidden state must be labelled Show balances")
            .that(button)
            .contains("stringResource(R.string.balance_show_balances)")
        assertWithMessage("revealed state must be labelled Hide balances")
            .that(button)
            .contains("stringResource(R.string.balance_hide_balances)")
        assertWithMessage("masked value must be hidden from TalkBack")
            .that(source("ui/components/MaskedBalance.kt"))
            .contains("clearAndSetSemantics")
    }
}
