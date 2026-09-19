package app.clearsms.ui.navigation

import app.clearsms.domain.model.EnabledSections
import app.clearsms.domain.model.StartDestination
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The bottom bar may never be composed over a screen that is not a bar
 * screen (issue #39: the bar flashed into the conversation during the back
 * transition, because the current back-stack entry flips to the tab route
 * the moment the pop starts). Visibility is a pure function of
 * (current route, the NavHost's still-visible routes, enabled sections).
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

    @Test
    fun `settled on a top-level tab shows the bar`() {
        tabs.forEach { tab ->
            assertThat(BottomBarVisibility.isVisible(tab, listOf(tab), allOn)).isTrue()
        }
    }

    @Test
    fun `settled on a non-tab screen hides the bar`() {
        listOf(Routes.CONVERSATION, Routes.SETTINGS, Routes.SEARCH, Routes.COMPOSE, Routes.ACCOUNT_DETAIL).forEach { route ->
            assertThat(BottomBarVisibility.isVisible(route, listOf(route), allOn)).isFalse()
        }
    }

    @Test
    fun `mid BACK transition the bar waits for the outgoing conversation to leave`() {
        // Pop just started: current entry already flipped to the tab, but the
        // conversation is still on the glass. THE issue-#39 frame.
        assertThat(
            BottomBarVisibility.isVisible(Routes.INBOX, listOf(Routes.INBOX, Routes.CONVERSATION), allOn),
        ).isFalse()
        // Transition complete: only the tab remains visible - bar appears now.
        assertThat(BottomBarVisibility.isVisible(Routes.INBOX, listOf(Routes.INBOX), allOn)).isTrue()
    }

    @Test
    fun `mid BACK transition from settings and search the bar also waits`() {
        listOf(Routes.SETTINGS, Routes.SEARCH, Routes.COMPOSE).forEach { outgoing ->
            tabs.forEach { tab ->
                assertThat(BottomBarVisibility.isVisible(tab, listOf(tab, outgoing), allOn)).isFalse()
            }
        }
    }

    @Test
    fun `mid FORWARD transition the bar hides immediately and never overlaps`() {
        // navigate(conversation): current entry is the conversation while the
        // tab is still fading out - removing the bar early overlaps nothing.
        assertThat(
            BottomBarVisibility.isVisible(Routes.CONVERSATION, listOf(Routes.INBOX, Routes.CONVERSATION), allOn),
        ).isFalse()
    }

    @Test
    fun `tab-to-tab switches keep the bar composed for the whole transition`() {
        // Both routes are bar routes: the bar must not blink during the
        // v0.17.2 saveState-restoreState tab switch.
        assertThat(
            BottomBarVisibility.isVisible(Routes.FINANCE, listOf(Routes.INBOX, Routes.FINANCE), allOn),
        ).isTrue()
    }

    @Test
    fun `every flag combination - bar only with two or more sections, never over a non-tab screen`() {
        allCombinations.forEach { sections ->
            val start =
                when (sections.resolveStart(StartDestination.INBOX)) {
                    StartDestination.INBOX -> Routes.INBOX
                    StartDestination.FINANCE -> Routes.FINANCE
                    StartDestination.ALERTS -> Routes.ALERTS
                }
            // Settled on the resolved start tab: bar iff >= 2 sections.
            assertThat(BottomBarVisibility.isVisible(start, listOf(start), sections))
                .isEqualTo(sections.showBottomBar)
            // Mid back-transition onto the same start tab: always hidden.
            assertThat(BottomBarVisibility.isVisible(start, listOf(start, Routes.CONVERSATION), sections)).isFalse()
        }
    }

    @Test
    fun `first composition with no visible entries yet shows no bar`() {
        assertThat(BottomBarVisibility.isVisible(null, emptyList(), allOn)).isFalse()
    }
}
