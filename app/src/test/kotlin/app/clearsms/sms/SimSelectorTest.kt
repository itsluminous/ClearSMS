package app.clearsms.sms

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** In-memory [SubscriptionSource] for selector tests - no framework classes. */
class FakeSubscriptionSource(
    private val sims: List<SimInfo>,
    private val defaultSub: Int? = null,
) : SubscriptionSource {
    override fun activeSims(): List<SimInfo> = sims

    override fun defaultSmsSubscriptionId(): Int? = defaultSub
}

class SimSelectorTest {
    private val sim1 = SimInfo(subscriptionId = 3, slotIndex = 0, displayName = "Airtel")
    private val sim2 = SimInfo(subscriptionId = 7, slotIndex = 1, displayName = "Jio")
    private val dual = listOf(sim1, sim2)

    // region fallback chain

    @Test
    fun `remembered per-recipient choice wins over everything`() {
        assertThat(SimSelector.choose(dual, remembered = 7, lastUsedInThread = 3, defaultSubscriptionId = 3))
            .isEqualTo(7)
    }

    @Test
    fun `no memory falls back to the SIM the thread last used`() {
        assertThat(SimSelector.choose(dual, remembered = null, lastUsedInThread = 7, defaultSubscriptionId = 3))
            .isEqualTo(7)
    }

    @Test
    fun `no memory and no thread history falls back to the system default`() {
        assertThat(SimSelector.choose(dual, remembered = null, lastUsedInThread = null, defaultSubscriptionId = 7))
            .isEqualTo(7)
    }

    @Test
    fun `every rung must be ACTIVE - a removed remembered SIM falls through`() {
        // Remembered sub 99 was removed; thread history 7 is still active.
        assertThat(SimSelector.choose(dual, remembered = 99, lastUsedInThread = 7, defaultSubscriptionId = 3))
            .isEqualTo(7)
        // Everything stale: first active SIM is the last resort.
        assertThat(SimSelector.choose(dual, remembered = 99, lastUsedInThread = 98, defaultSubscriptionId = 97))
            .isEqualTo(3)
    }

    @Test
    fun `no active SIMs yields null - send uses the default manager`() {
        assertThat(SimSelector.choose(emptyList(), remembered = 7, lastUsedInThread = 3, defaultSubscriptionId = 3))
            .isNull()
    }

    // endregion

    // region single vs dual behaviour through a fake subscription source

    @Test
    fun `single-SIM device - indicator hidden and selection is that SIM`() {
        val source = FakeSubscriptionSource(listOf(sim1), defaultSub = 3)
        val sims = source.activeSims()
        assertThat(SimSelector.indicatorVisible(sims)).isFalse()
        assertThat(SimSelector.choose(sims, null, null, source.defaultSmsSubscriptionId())).isEqualTo(3)
    }

    @Test
    fun `dual-SIM device - indicator visible`() {
        val source = FakeSubscriptionSource(dual, defaultSub = 3)
        assertThat(SimSelector.indicatorVisible(source.activeSims())).isTrue()
    }

    @Test
    fun `no telephony or permission - empty sims keep everything off`() {
        val source = FakeSubscriptionSource(emptyList())
        assertThat(SimSelector.indicatorVisible(source.activeSims())).isFalse()
        assertThat(SimSelector.choose(source.activeSims(), null, null, null)).isNull()
    }

    // endregion

    @Test
    fun `tap cycles through SIMs in slot order and wraps`() {
        assertThat(SimSelector.next(dual, current = 3)).isEqualTo(7)
        assertThat(SimSelector.next(dual, current = 7)).isEqualTo(3)
        // Unknown current lands on the first slot.
        assertThat(SimSelector.next(dual, current = null)).isEqualTo(3)
        assertThat(SimSelector.next(emptyList(), current = 3)).isNull()
    }

    @Test
    fun `bubble SIM tags need 2+ SIMs on device OR in the corpus`() {
        assertThat(SimSelector.showSimTags(listOf(sim1), corpusSubscriptionIds = listOf(3))).isFalse()
        assertThat(SimSelector.showSimTags(dual, corpusSubscriptionIds = emptyList())).isTrue()
        // The device is back to one SIM but old messages span two.
        assertThat(SimSelector.showSimTags(listOf(sim1), corpusSubscriptionIds = listOf(3, 7))).isTrue()
    }

    @Test
    fun `slot labels are 1-based and null for unknown subscriptions`() {
        assertThat(SimSelector.slotLabelFor(dual, 3)).isEqualTo("SIM 1")
        assertThat(SimSelector.slotLabelFor(dual, 7)).isEqualTo("SIM 2")
        assertThat(SimSelector.slotLabelFor(dual, 99)).isNull()
        assertThat(SimSelector.slotLabelFor(dual, null)).isNull()
    }
}
