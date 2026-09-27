package app.clearsms.ui.navigation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.clearsms.domain.model.EnabledSections
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Issue #47: scroll the inbox to its end, open a conversation, come back -
 * the last row sat under the bottom bar.
 *
 * Mechanism, confirmed in the shell: the bar's AnimatedVisibility slot is
 * keyed on the TARGET route and its exit snaps, so a forward navigation
 * zeroes the scaffold's content padding while the inbox is still on the
 * glass for its 700 ms fade-out. Its list grows by the bar's height, a list
 * scrolled to its end fills the space by scrolling BACK, and NavHost saves
 * that shifted position with the entry. Coming back, the slot expands from
 * zero, so the restored list measures a full-height viewport first and is
 * squeezed by the bar over the crossfade - and a lazy list never scrolls
 * FORWARD to keep its end in view. Either half alone leaves the last row
 * beneath the bar.
 *
 * The fix keeps a TAB's bottom inset independent of the animating slot:
 * [BottomBarVisibility.tabContentInset] is the bar's RESTING height for as
 * long as the sections produce a bar, zero otherwise, and the shell tops
 * the slot padding up to it ([BottomBarVisibility.tabInsetTopUp]). No
 * timer, and no permanent strip: at rest the top-up is exactly zero.
 */
class TabContentInsetTest {
    private val settled = 80.dp

    /** Every flag combination the settings can produce (all-off is healed by [EnabledSections.from]). */
    private val allCombinations =
        listOf(true, false)
            .flatMap { i ->
                listOf(true, false).flatMap { f ->
                    listOf(true, false).map { a -> EnabledSections.from(inbox = i, finance = f, alerts = a) }
                }
            }.distinct()

    /**
     * The slot's height at the moments that matter: snapped away under an
     * OUTGOING tab, part-way through the expand under an INCOMING tab, and
     * fully at rest.
     */
    private val slotHeights: List<Dp> = listOf(0.dp, 1.dp, 24.dp, 56.dp, 79.dp, 80.dp)

    @Test
    fun `a tab's total inset is the bar's resting height at every point of a transition`() {
        allCombinations.filter { it.showBottomBar }.forEach { sections ->
            slotHeights.forEach { slot ->
                val topUp = BottomBarVisibility.tabInsetTopUp(sections, settled, slot)
                // Slot + top-up is what the tab actually lays out against;
                // the shifting list of #47 is exactly this sum changing.
                assertThat(slot + topUp).isEqualTo(settled)
            }
        }
    }

    @Test
    fun `outgoing tab - the slot has snapped away but the tab keeps the full inset`() {
        val sections = EnabledSections()
        assertThat(BottomBarVisibility.tabInsetTopUp(sections, settled, slotHeight = 0.dp)).isEqualTo(settled)
    }

    @Test
    fun `incoming tab - the expanding slot is topped up to the resting height, never beyond`() {
        val sections = EnabledSections()
        assertThat(BottomBarVisibility.tabInsetTopUp(sections, settled, slotHeight = 30.dp)).isEqualTo(50.dp)
        // Never negative: a slot taller than the recorded resting height
        // (it was just re-measured larger) adds nothing.
        assertThat(BottomBarVisibility.tabInsetTopUp(sections, settled, slotHeight = 96.dp)).isEqualTo(0.dp)
    }

    @Test
    fun `at rest the top-up is zero - no permanent strip above the bar`() {
        allCombinations.forEach { sections ->
            val atRest = if (sections.showBottomBar) settled else 0.dp
            assertThat(BottomBarVisibility.tabInsetTopUp(sections, settled, slotHeight = atRest)).isEqualTo(0.dp)
        }
    }

    @Test
    fun `with fewer than two sections there is no bar and no inset, whatever height was once measured`() {
        allCombinations.filterNot { it.showBottomBar }.forEach { sections ->
            assertThat(BottomBarVisibility.tabContentInset(sections, settled)).isEqualTo(0.dp)
            slotHeights.forEach { slot ->
                // The stale measurement of a bar that has since been toggled
                // away must not leave a dead strip on the one remaining tab.
                assertThat(BottomBarVisibility.tabInsetTopUp(sections, settled, slot)).isEqualTo(0.dp)
            }
        }
    }

    @Test
    fun `before the bar has ever been measured the tab lays out against the slot alone`() {
        // Cold start: the bar is composed at rest in the first frame, so the
        // slot IS the resting height and a zero measurement changes nothing.
        allCombinations.forEach { sections ->
            slotHeights.forEach { slot ->
                assertThat(BottomBarVisibility.tabInsetTopUp(sections, settledBarHeight = 0.dp, slotHeight = slot))
                    .isEqualTo(0.dp)
            }
        }
    }

    // ------------------------------------------------------------------
    // Shell wiring, source-pinned: the pure function is only a fix if the
    // tabs use it and the non-tab routes do not.
    // ------------------------------------------------------------------

    private val shell = File("src/main/kotlin/app/clearsms/ui/navigation/ClearSmsApp.kt").readText()

    @Test
    fun `exactly the three tab routes lay out through TabInset`() {
        val tabConstants = mapOf(Routes.INBOX to "INBOX", Routes.FINANCE to "FINANCE", Routes.ALERTS to "ALERTS")
        assertThat(tabConstants.keys).containsExactlyElementsIn(Routes.topLevel)
        tabConstants.values.forEach { name ->
            assertThat(shell).containsMatch("""composable\(Routes\.$name\) \{\s*TabInset\(padding, sections, settledBarHeight\)""")
        }
        // Non-tab routes keep the live slot padding, so the arriving bar
        // still pushes an outgoing conversation up rather than drawing over
        // it (the #39 construction).
        assertThat(shell.split("TabInset(padding").size - 1).isEqualTo(Routes.topLevel.size)
    }

    @Test
    fun `the resting height is read off the bar itself, not a duplicated Material constant`() {
        assertThat(shell).contains("NavigationBar(modifier = Modifier.onSizeChanged { settledBarHeightPx = it.height })")
        // Survives an activity recreated on a conversation, so the first
        // back transition after it is stable too.
        assertThat(shell).contains("rememberSaveable { mutableStateOf(0) }")
    }

    @Test
    fun `the top-up consumes exactly what it pads`() {
        // Padding without consuming would let the nested scaffold pad for the
        // navigation bar a second time - the dead strip the inset-ownership
        // convention removed.
        assertThat(shell).contains(".padding(bottom = topUp)")
        assertThat(shell).contains(".consumeWindowInsets(PaddingValues(bottom = topUp))")
    }

    @Test
    fun `the top-up is the pure decision - no hand-tuned padding in the shell`() {
        assertThat(shell).contains(
            "BottomBarVisibility.tabInsetTopUp(sections, settledBarHeight, slotPadding.calculateBottomPadding())",
        )
    }
}
