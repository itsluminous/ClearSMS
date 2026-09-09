package app.clearsms.ui.components

import app.clearsms.sms.SimInfo
import app.clearsms.sms.SimSelector
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Regression tests for the dual-SIM tap toast (release-build report: tapping
 * the indicator switched to SIM 2 but the toast said "SIM 1"). Root cause:
 * the compose bar's click lambda toasted the composition-captured [SimUiState]
 * - the PRE-cycle value - so the toast lagged one step behind the switch. The
 * long-press hint reads the same captured state but mutates nothing, which is
 * why only the tap was wrong. Pure logic plus a source scan, per the repo's
 * no-Compose-harness convention.
 */
class SimCycleToastTest {
    // Two active SIMs; slots are 0-based in SimInfo, 1-based in labels.
    private val slot0 = SimInfo(subscriptionId = 10, slotIndex = 0, displayName = "Carrier A")
    private val slot1 = SimInfo(subscriptionId = 20, slotIndex = 1, displayName = "Carrier B")
    private val active = listOf(slot0, slot1)

    /** The post-switch UI state, exactly as both ViewModels build it. */
    private fun stateFor(
        activeSims: List<SimInfo>,
        chosen: Int?,
    ): SimUiState {
        val info = activeSims.firstOrNull { it.subscriptionId == chosen }
        return SimUiState(
            visible = SimSelector.indicatorVisible(activeSims),
            slot = SimSelector.slotNumberFor(activeSims, chosen) ?: 0,
            simCount = activeSims.size,
            operatorName = info?.displayName.orEmpty(),
            iconTint = info?.iconTint,
        )
    }

    private fun cycle(
        activeSims: List<SimInfo>,
        current: Int?,
    ): SimUiState? = SimSelector.next(activeSims, current)?.let { stateFor(activeSims, it) }

    @Test
    fun `cycling from slot 0 labels the NEWLY selected subscription`() {
        val switched = requireNotNull(cycle(active, current = slot0.subscriptionId))
        assertThat(switched.slot).isEqualTo(2)
        assertThat(switched.tapLabel).isEqualTo("SIM 2 - Carrier B")
    }

    @Test
    fun `cycling from the last slot wraps back to slot 0`() {
        val switched = requireNotNull(cycle(active, current = slot1.subscriptionId))
        assertThat(switched.slot).isEqualTo(1)
        assertThat(switched.tapLabel).isEqualTo("SIM 1 - Carrier A")
    }

    @Test
    fun `tap toast and long-press hint always name the same subscription`() {
        // Both labels derive from the ONE state: whatever subscription the
        // hint names, the toast names - in both cycle directions.
        var state = stateFor(active, slot0.subscriptionId)
        repeat(4) {
            assertThat(state.hintLabel).isEqualTo("Sends with ${state.tapLabel}")
            state = requireNotNull(cycle(active, active.first { it.slotIndex + 1 == state.slot }.subscriptionId))
        }
    }

    @Test
    fun `an inactive third subscription is never offered or named`() {
        val inactiveSubId = 30 // exists on the device, not in activeSims
        // Cycling only ever visits the two ACTIVE subscriptions.
        var current: Int? = slot0.subscriptionId
        val visited = mutableSetOf<Int>()
        repeat(6) {
            current = requireNotNull(SimSelector.next(active, current))
            visited += requireNotNull(current)
        }
        assertThat(visited).containsExactly(slot0.subscriptionId, slot1.subscriptionId)
        // A stale per-recipient memory of the inactive sub falls through.
        val chosen =
            SimSelector.choose(
                activeSims = active,
                remembered = inactiveSubId,
                lastUsedInThread = null,
                defaultSubscriptionId = slot0.subscriptionId,
            )
        assertThat(chosen).isEqualTo(slot0.subscriptionId)
        // And it can never be labelled: it is unknown to the active list.
        assertThat(SimSelector.slotNumberFor(active, inactiveSubId)).isNull()
    }

    @Test
    fun `the tap toast reads the post-switch state cycleSim returns - never the captured pre-cycle one`() {
        // Source scan (the repo's convention-test pattern): inside the tap
        // handler the toast must be built from the value onCycleSim RETURNS.
        // Toasting `sim.tapLabel` there is the regression - `sim` is the
        // state captured at composition time, one step behind the switch.
        val bar = File("src/main/kotlin/app/clearsms/ui/components/MessageComposerBar.kt").readText()
        val tap =
            bar
                .substringAfter("if (sim.visible)")
                .substringAfter("onClick =")
                .substringBefore("onClickLabel")
        assertThat(tap).contains("onCycleSim()?.let")
        assertThat(tap).contains("switched.tapLabel")
        assertThat(tap).doesNotContain("sim.tapLabel")
    }
}
