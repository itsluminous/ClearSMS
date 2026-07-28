package app.clearsms.ui.finance

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The session reveal flag behind the "Show balance" privacy gate. */
class BalanceVisibilityTest {
    @Test
    fun `starts concealed - a fresh session never shows gated balances`() {
        assertThat(BalanceVisibility().revealed.value).isFalse()
    }

    @Test
    fun `reveal exposes and conceal re-masks`() {
        val visibility = BalanceVisibility()

        visibility.reveal()
        assertThat(visibility.revealed.value).isTrue()

        // Backgrounding, eye-tap-to-hide and setting writes all call conceal.
        visibility.conceal()
        assertThat(visibility.revealed.value).isFalse()
    }

    @Test
    fun `conceal is idempotent and reveal can follow a conceal`() {
        val visibility = BalanceVisibility()
        visibility.conceal()
        visibility.conceal()
        assertThat(visibility.revealed.value).isFalse()

        visibility.reveal()
        assertThat(visibility.revealed.value).isTrue()
    }
}
