package app.clearsms.ui.components

import androidx.compose.ui.graphics.Color
import app.clearsms.sms.SimInfo
import app.clearsms.sms.SimSelector
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The compose-bar SIM icon: slot-number mapping from the chosen
 * subscription and the accessibility description read to screen readers.
 */
class SimIndicatorTest {
    private val sims =
        listOf(
            SimInfo(subscriptionId = 10, slotIndex = 0, displayName = "Airtel"),
            SimInfo(subscriptionId = 20, slotIndex = 1, displayName = "Jio"),
        )

    @Test
    fun `slot number is the 1-based physical slot of the chosen subscription`() {
        assertThat(SimSelector.slotNumberFor(sims, 10)).isEqualTo(1)
        assertThat(SimSelector.slotNumberFor(sims, 20)).isEqualTo(2)
    }

    @Test
    fun `unknown subscription maps to no slot number`() {
        assertThat(SimSelector.slotNumberFor(sims, 99)).isNull()
        assertThat(SimSelector.slotNumberFor(sims, null)).isNull()
        assertThat(SimSelector.slotNumberFor(emptyList(), 10)).isNull()
    }

    @Test
    fun `content description names the slot, the sim count and the operator`() {
        val state = SimUiState(visible = true, slot = 1, simCount = 2, operatorName = "Airtel")
        assertThat(state.contentDescription).isEqualTo("SIM 1 of 2 - Airtel")
    }

    @Test
    fun `tap label leads with the slot so same-carrier SIMs stay distinguishable`() {
        // Same operator on both SIMs: only the slot tells them apart.
        val slot1 = SimUiState(visible = true, slot = 1, simCount = 2, operatorName = "Airtel")
        val slot2 = SimUiState(visible = true, slot = 2, simCount = 2, operatorName = "Airtel")
        assertThat(slot1.tapLabel).isEqualTo("SIM 1 - Airtel")
        assertThat(slot2.tapLabel).isEqualTo("SIM 2 - Airtel")
        assertThat(slot1.tapLabel).isNotEqualTo(slot2.tapLabel)
    }

    @Test
    fun `blank operator name degrades to the bare slot label`() {
        val state = SimUiState(visible = true, slot = 2, simCount = 2, operatorName = "")
        assertThat(state.tapLabel).isEqualTo("SIM 2")
        assertThat(state.contentDescription).isEqualTo("SIM 2 of 2")
    }

    @Test
    fun `user-assigned nickname flows through as the name after the slot`() {
        // DeviceSubscriptionSource maps SubscriptionInfo.displayName - the
        // user's nickname when one is set - into SimInfo.displayName, which
        // the ViewModels pass here as operatorName.
        val state = SimUiState(visible = true, slot = 1, simCount = 2, operatorName = "Work")
        assertThat(state.tapLabel).isEqualTo("SIM 1 - Work")
    }

    // --- long-press identity hint (GitHub #7, round 2) ---------------------

    @Test
    fun `long-press hint names the slot and the name without implying a switch`() {
        val state = SimUiState(visible = true, slot = 1, simCount = 2, operatorName = "Airtel")
        assertThat(state.hintLabel).isEqualTo("Sends with SIM 1 - Airtel")
        // Distinct from the tap toast, which announces the NEW choice after
        // a cycle - long-press must read as "what is this", not "switched".
        assertThat(state.hintLabel).isNotEqualTo(state.tapLabel)
    }

    @Test
    fun `long-press hint stays unambiguous with the same carrier on both SIMs`() {
        // The reporter's exact setup: dual SIMs, identical carrier name.
        val slot1 = SimUiState(visible = true, slot = 1, simCount = 2, operatorName = "Vodafone")
        val slot2 = SimUiState(visible = true, slot = 2, simCount = 2, operatorName = "Vodafone")
        assertThat(slot1.hintLabel).isEqualTo("Sends with SIM 1 - Vodafone")
        assertThat(slot2.hintLabel).isEqualTo("Sends with SIM 2 - Vodafone")
        assertThat(slot1.hintLabel).isNotEqualTo(slot2.hintLabel)
    }

    @Test
    fun `long-press hint degrades to the bare slot when no name exists`() {
        val state = SimUiState(visible = true, slot = 2, simCount = 2, operatorName = "")
        assertThat(state.hintLabel).isEqualTo("Sends with SIM 2")
    }

    // --- system SIM colour resolution (GitHub #7, round 2) -----------------

    private val lightSurface = Color(0xFFFDFBFF)
    private val darkSurface = Color(0xFF1A1C1E)
    private val fallback = Color(0xFF44474E)

    @Test
    fun `a legible system tint is used as-is`() {
        // Teal 700, the classic platform SIM tint: dark enough for light
        // surfaces (contrast well above the 3 to 1 non-text minimum).
        val teal = Color(0xFF00796B)
        assertThat(simIndicatorTint(teal, lightSurface, fallback)).isEqualTo(teal)
    }

    @Test
    fun `absent system tint falls back to the theme colour`() {
        assertThat(simIndicatorTint(null, lightSurface, fallback)).isEqualTo(fallback)
        assertThat(simIndicatorTint(null, darkSurface, fallback)).isEqualTo(fallback)
    }

    @Test
    fun `an illegible tint falls back instead of vanishing`() {
        // A pale platform tint on a light surface: below 3 to 1, unreadable.
        val pale = Color(0xFFE0E0E0)
        assertThat(simIndicatorTint(pale, lightSurface, fallback)).isEqualTo(fallback)
        // A near-black tint on a dark surface likewise.
        val nearBlack = Color(0xFF212121)
        assertThat(simIndicatorTint(nearBlack, darkSurface, fallback)).isEqualTo(fallback)
    }

    @Test
    fun `the same tint can be legible in one theme and fall back in the other`() {
        // Indigo reads fine on a light surface but not on a dark one - the
        // decision must be per-theme, not baked in once.
        val indigo = Color(0xFF283593)
        assertThat(simIndicatorTint(indigo, lightSurface, fallback)).isEqualTo(indigo)
        assertThat(simIndicatorTint(indigo, darkSurface, fallback)).isEqualTo(fallback)
    }

    @Test
    fun `a translucent system tint is judged and drawn opaque`() {
        // Some OEMs report tints with alpha; the indicator must not become
        // a ghost of itself, so alpha is discarded before the decision.
        val translucentTeal = Color(0x8000796B)
        val resolved = simIndicatorTint(translucentTeal, lightSurface, fallback)
        assertThat(resolved).isEqualTo(Color(0xFF00796B))
    }

    @Test
    fun `duplicate tints across SIMs resolve identically - the digit disambiguates`() {
        // Both SIMs sharing one colour is valid platform state: neither is
        // rejected, and telling them apart stays the slot digit's job.
        val shared = Color(0xFF00796B)
        assertThat(simIndicatorTint(shared, lightSurface, fallback))
            .isEqualTo(simIndicatorTint(shared, lightSurface, fallback))
    }
}
