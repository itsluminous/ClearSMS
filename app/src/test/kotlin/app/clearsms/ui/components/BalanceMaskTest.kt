package app.clearsms.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The mask placeholder and the exact masked-vs-visible rule. */
class BalanceMaskTest {
    @Test
    fun `mask carries no information - no digits, fixed shape`() {
        assertThat(BalanceMask.MASK).doesNotContainMatch("[0-9]")
        // Every balance masks to the identical string, so length or shape
        // can never hint at the hidden magnitude.
        assertThat(BalanceMask.MASK).isEqualTo("₹\u00A0••••••")
    }

    @Test
    fun `masked only when gated and not revealed`() {
        assertThat(BalanceMask.isMasked(gated = true, revealed = false)).isTrue()
        assertThat(BalanceMask.isMasked(gated = true, revealed = true)).isFalse()
        // Setting ON (not gated): never masked, regardless of session state.
        assertThat(BalanceMask.isMasked(gated = false, revealed = false)).isFalse()
        assertThat(BalanceMask.isMasked(gated = false, revealed = true)).isFalse()
    }
}
