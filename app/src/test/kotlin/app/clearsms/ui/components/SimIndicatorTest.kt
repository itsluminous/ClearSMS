package app.clearsms.ui.components

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
}
