package app.clearsms.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The mask placeholder and the exact masked-vs-visible rule. */
class BalanceMaskTest {
    @Test
    fun `mask carries no information - no digits, fixed shape`() {
        assertThat(BalanceMask.mask("INR")).doesNotContainMatch("[0-9]")
        // Every balance in a currency masks to the identical string, so
        // length or shape can never hint at the hidden magnitude. Indian
        // users keep the exact rupee placeholder they always had.
        assertThat(BalanceMask.mask("INR")).isEqualTo("₹\u00A0••••••")
        // Other currencies keep their own symbol - never a rupee sign.
        assertThat(BalanceMask.mask("CLP")).isEqualTo("$\u00A0••••••")
        assertThat(BalanceMask.mask("USD")).isEqualTo("US$\u00A0••••••")
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
