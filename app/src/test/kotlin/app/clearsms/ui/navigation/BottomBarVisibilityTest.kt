package app.clearsms.ui.navigation

import app.clearsms.domain.model.EnabledSections
import app.clearsms.domain.model.StartDestination
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * The bottom bar must arrive WITH the tab content - not before it and not
 * after it. Both failure modes shipped once:
 *
 *  - issue #39: keyed on the current entry alone and composed at full
 *    opacity, the bar flashed OVER the conversation the moment a pop
 *    started (the entry flips to the tab route at pop time, a full
 *    crossfade before the conversation leaves the glass);
 *  - the follow-up: gating composition on NavController.visibleEntries made
 *    the bar wait for the WHOLE 700 ms exit fade, so the settled inbox sat
 *    bare for a beat - too late instead of too early.
 *
 * The pinned resolution: [BottomBarVisibility.isVisible] answers only WHO
 * owns the bar (the transition's TARGET destination), and the shell answers
 * WHEN by animating the bar's slot on the same spec as the NavHost's route
 * crossfade ([BottomBarVisibility.contentTransitionSpec]). The timing lives
 * in wiring, not in a boolean, so the wiring is source-pinned below.
 */
class BottomBarVisibilityTest {
    private val allOn = EnabledSections()
    private val tabs = Routes.topLevel.toList()

    /** Every flag combination the settings can produce (all-off is healed by [EnabledSections.from]). */
    private val allCombinations =
        listOf(true, false)
            .flatMap { i ->
                listOf(true, false).flatMap { f ->
                    listOf(true, false).map { a -> EnabledSections.from(inbox = i, finance = f, alerts = a) }
                }
            }.distinct()

    // ------------------------------------------------------------------
    // WHO owns the bar: pure target-keyed visibility.
    // ------------------------------------------------------------------

    @Test
    fun `settled on a top-level tab shows the bar`() {
        tabs.forEach { tab ->
            assertThat(BottomBarVisibility.isVisible(tab, allOn)).isTrue()
        }
    }

    @Test
    fun `settled on a non-tab screen hides the bar`() {
        listOf(Routes.CONVERSATION, Routes.SETTINGS, Routes.SEARCH, Routes.COMPOSE, Routes.ACCOUNT_DETAIL).forEach { route ->
            assertThat(BottomBarVisibility.isVisible(route, allOn)).isFalse()
        }
    }

    @Test
    fun `mid BACK transition the bar already belongs to the target tab`() {
        // The pop has just started: the current entry is the tab while the
        // conversation is still fading out. The bar must be COMPOSED now -
        // its enter animation (not this boolean) synchronises the arrival.
        // The visibleEntries gate returned false here; that is the reverted
        // "too late" behaviour, and this is the test that fails on it.
        tabs.forEach { tab ->
            assertThat(BottomBarVisibility.isVisible(tab, allOn)).isTrue()
        }
    }

    @Test
    fun `mid FORWARD transition the bar leaves immediately with the outgoing tab`() {
        // navigate(conversation): the current entry is already the
        // conversation while the tab fades out - target-keyed visibility is
        // false at once, and the shell's snap() exit removes the slot in the
        // same frame (pinned below), so the bar never overlaps the
        // conversation in either direction.
        assertThat(BottomBarVisibility.isVisible(Routes.CONVERSATION, allOn)).isFalse()
    }

    @Test
    fun `tab-to-tab switches keep the bar composed for the whole transition`() {
        // Source and target are both bar routes, so visibility never flips
        // mid-switch and the v0.17.2 saveState/restoreState contract sees an
        // uninterrupted bar.
        tabs.forEach { tab -> assertThat(BottomBarVisibility.isVisible(tab, allOn)).isTrue() }
    }

    @Test
    fun `every flag combination - bar only with two or more sections, and for the resolved start`() {
        allCombinations.forEach { sections ->
            val start =
                when (sections.resolveStart(StartDestination.INBOX)) {
                    StartDestination.INBOX -> Routes.INBOX
                    StartDestination.FINANCE -> Routes.FINANCE
                    StartDestination.ALERTS -> Routes.ALERTS
                }
            assertThat(BottomBarVisibility.isVisible(start, sections))
                .isEqualTo(sections.showBottomBar)
            // A non-tab target never shows the bar, whatever the flags.
            assertThat(BottomBarVisibility.isVisible(Routes.CONVERSATION, sections)).isFalse()
        }
    }

    @Test
    fun `first composition with no route yet shows no bar`() {
        assertThat(BottomBarVisibility.isVisible(null, allOn)).isFalse()
    }

    // ------------------------------------------------------------------
    // WHEN it arrives: the shell wiring, source-pinned. Reverting either
    // half (the visibleEntries gate, or a hard if with no animation)
    // fails here.
    // ------------------------------------------------------------------

    private val shell = File("src/main/kotlin/app/clearsms/ui/navigation/ClearSmsApp.kt").readText()

    @Test
    fun `the bar slot animates in - keyed on the target, on the shared content spec`() {
        // AnimatedVisibility keyed on the pure decision...
        assertThat(shell).contains("visible = BottomBarVisibility.isVisible(currentRoute, sections)")
        // ...entering on the SAME spec as the route crossfade (fade so the
        // bar's opacity tracks the incoming content, expand so the growing
        // slot pushes the outgoing screen up - overlap impossible).
        assertThat(shell).contains("fadeIn(BottomBarVisibility.contentTransitionSpec())")
        assertThat(shell).contains("expandVertically(BottomBarVisibility.contentTransitionSpec())")
        // ...and leaving instantly on forward navigation (chrome removed
        // early overlaps nothing; both prior versions behaved this way).
        assertThat(shell).contains("exit = shrinkVertically(snap()) + fadeOut(snap())")
    }

    @Test
    fun `the NavHost route crossfade runs on the same shared spec`() {
        // The synchronisation argument only holds if the content transition
        // is the SAME clock - explicit, not an implicit library default that
        // could drift apart from the bar's.
        assertThat(shell).contains("enterTransition = { fadeIn(BottomBarVisibility.contentTransitionSpec()) }")
        assertThat(shell).contains("exitTransition = { fadeOut(BottomBarVisibility.contentTransitionSpec()) }")
    }

    @Test
    fun `the too-late visibleEntries gate stays out`() {
        // NavController.visibleEntries keeps the outgoing entry until its
        // exit animation COMPLETES - gating composition on it is exactly the
        // "bar arrives a beat after the inbox" regression.
        assertThat(shell).doesNotContain("visibleEntries")
    }

    @Test
    fun `no timers or ad-hoc durations - the only clock is the named shared spec`() {
        // The bar must not be "tuned" into sync: no delay() and no literal
        // duration anywhere in the shell; the single 700 lives in
        // BottomBarVisibility as the named navigation-compose default.
        assertThat(shell).doesNotContain("delay(")
        assertThat(shell).doesNotContain("tween(")
        assertThat(shell).doesNotContain("700")
    }
}
