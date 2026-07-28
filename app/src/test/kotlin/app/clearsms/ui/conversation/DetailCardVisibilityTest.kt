package app.clearsms.ui.conversation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * "Show extracted message details" now genuinely controls the parsed detail
 * card under conversation bubbles (it used to be written but never read).
 */
class DetailCardVisibilityTest {
    private val details = mapOf("amount" to "500.0", "type" to "debit")

    @Test
    fun `enabled with parsed details - card shows`() {
        assertThat(DetailCardVisibility.shouldShow(details, showTransactionDetails = true)).isTrue()
    }

    @Test
    fun `disabled - card hidden even when details exist`() {
        assertThat(DetailCardVisibility.shouldShow(details, showTransactionDetails = false)).isFalse()
    }

    @Test
    fun `no parsed details - nothing to show either way`() {
        assertThat(DetailCardVisibility.shouldShow(emptyMap(), showTransactionDetails = true)).isFalse()
        assertThat(DetailCardVisibility.shouldShow(emptyMap(), showTransactionDetails = false)).isFalse()
    }
}
