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
}
