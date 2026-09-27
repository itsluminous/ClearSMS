package app.clearsms.ui.navigation

import app.clearsms.domain.model.FinanceTab
import app.clearsms.domain.model.InboxPill
import app.clearsms.ui.alerts.AlertFilter
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The shared pill-visibility mechanism as pure logic, exercised with the
 * pill sets of all three screens: which pills the row shows for EVERY
 * combination of hidden pills (all-hidden included), that order and
 * visibility stay independent, and the [activePill] guard that keeps a
 * hidden pill from staying selected on each screen.
 */
class PillConfigTest {
    private fun <T> everySubset(all: List<T>): Sequence<Set<T>> =
        (0 until (1 shl all.size)).asSequence().map { mask ->
            all.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
        }

    private fun <T> assertEveryCombinationResolves(all: List<T>) {
        var seen = 0
        for (hidden in everySubset(all)) {
            val config = PillConfig(all, hidden = hidden)
            assertThat(config.visible).containsExactlyElementsIn(all - hidden).inOrder()
            assertThat(config.ordered).isEqualTo(all)
            assertThat(config.showsRow).isEqualTo(hidden.size < all.size)
            seen++
        }
        assertThat(seen).isEqualTo(1 shl all.size)
    }

    @Test
    fun `finance - every combination of hidden tabs resolves to its complement, 16 in all`() {
        assertEveryCombinationResolves(FinanceTab.entries.toList())
    }

    @Test
    fun `alerts - every combination of hidden filters resolves to its complement, 512 in all`() {
        assertEveryCombinationResolves(AlertFilter.entries.toList())
    }

    @Test
    fun `inbox - the same mechanism, every combination`() {
        assertEveryCombinationResolves(InboxPill.entries.toList())
    }

    @Test
    fun `all hidden - the row disappears on every screen, nothing is forced back on`() {
        for (all in listOf(FinanceTab.entries.toList(), AlertFilter.entries.toList(), InboxPill.entries.toList())) {
            val config = PillConfig(all, hidden = all.toSet())
            assertThat(config.visible).isEmpty()
            assertThat(config.showsRow).isFalse()
            assertThat(config.hidden).isEqualTo(all.toSet())
        }
    }

    @Test
    fun `hiding never disturbs the stored order - a re-shown pill returns to its place`() {
        val order = listOf(AlertFilter.TRAVEL, AlertFilter.ALL, AlertFilter.DELIVERY)
        val hidden = PillConfig(AlertFilter.entries.toList(), order, setOf(AlertFilter.ALL))
        assertThat(hidden.visible.take(2)).containsExactly(AlertFilter.TRAVEL, AlertFilter.DELIVERY).inOrder()
        val shown = hidden.copy(hidden = emptySet())
        assertThat(shown.visible.take(3)).isEqualTo(order)
    }

    @Test
    fun `order resolves leniently - unknown values dropped, missing pills appended`() {
        val config =
            PillConfig(
                all = FinanceTab.entries.toList(),
                order = listOf(FinanceTab.RECHARGES, FinanceTab.RECHARGES),
                hidden = setOf(FinanceTab.ACCOUNTS),
            )
        assertThat(config.ordered)
            .containsExactly(FinanceTab.RECHARGES, FinanceTab.ACCOUNTS, FinanceTab.CREDIT_CARDS, FinanceTab.TRANSACTIONS)
            .inOrder()
        assertThat(config.visible)
            .containsExactly(FinanceTab.RECHARGES, FinanceTab.CREDIT_CARDS, FinanceTab.TRANSACTIONS)
            .inOrder()
    }

    @Test
    fun `guard - a visible selection stays, a hidden one falls back`() {
        val visible = listOf(AlertFilter.ALL, AlertFilter.EMI)
        assertThat(activePill(AlertFilter.EMI, visible, AlertFilter.ALL)).isEqualTo(AlertFilter.EMI)
        assertThat(activePill(AlertFilter.TRAVEL, visible, AlertFilter.ALL)).isEqualTo(AlertFilter.ALL)
    }

    @Test
    fun `guard - alerts fall back to the unfiltered ALL view even when its own chip is hidden`() {
        // ALL is the fallback, not a filter: with its chip hidden the list
        // still shows everything and no chip is selected.
        val config = PillConfig(AlertFilter.entries.toList(), hidden = setOf(AlertFilter.ALL, AlertFilter.EMI))
        assertThat(activePill(AlertFilter.EMI, config.visible, AlertFilter.ALL)).isEqualTo(AlertFilter.ALL)
        assertThat(activePill(AlertFilter.ALL, config.visible, AlertFilter.ALL)).isEqualTo(AlertFilter.ALL)
        assertThat(config.visible).doesNotContain(AlertFilter.ALL)
    }

    @Test
    fun `guard - finance falls back to the first visible tab, and to no tab when all are hidden`() {
        val some = PillConfig(FinanceTab.entries.toList(), hidden = setOf(FinanceTab.ACCOUNTS))
        assertThat(activePill(FinanceTab.ACCOUNTS, some.visible, some.visible.firstOrNull()))
            .isEqualTo(FinanceTab.CREDIT_CARDS)
        assertThat(activePill(FinanceTab.RECHARGES, some.visible, some.visible.firstOrNull()))
            .isEqualTo(FinanceTab.RECHARGES)

        val none = PillConfig(FinanceTab.entries.toList(), hidden = FinanceTab.entries.toSet())
        assertThat(activePill(FinanceTab.ACCOUNTS, none.visible, none.visible.firstOrNull())).isNull()
    }

    @Test
    fun `guard - the inbox's unfiltered view is no pill at all`() {
        val visible = listOf(InboxPill.IMPORTANT)
        assertThat(activePill(InboxPill.IMPORTANT, visible, null)).isEqualTo(InboxPill.IMPORTANT)
        assertThat(activePill(InboxPill.SPAM, visible, null)).isNull()
        assertThat(activePill<InboxPill?>(null, visible, null)).isNull()
    }

    @Test
    fun `guard - for every screen and every hidden combination, the active pill is never hidden`() {
        for (hidden in everySubset(AlertFilter.entries.toList())) {
            val config = PillConfig(AlertFilter.entries.toList(), hidden = hidden)
            for (selected in AlertFilter.entries) {
                val active = activePill(selected, config.visible, AlertFilter.ALL)
                // Hidden ALL is the one permitted exception: it is the unfiltered view itself.
                if (active != AlertFilter.ALL) assertThat(active).isIn(config.visible)
            }
        }
        for (hidden in everySubset(FinanceTab.entries.toList())) {
            val config = PillConfig(FinanceTab.entries.toList(), hidden = hidden)
            for (selected in FinanceTab.entries) {
                val active = activePill(selected, config.visible, config.visible.firstOrNull())
                if (active != null) assertThat(active).isIn(config.visible) else assertThat(config.showsRow).isFalse()
            }
        }
    }
}
