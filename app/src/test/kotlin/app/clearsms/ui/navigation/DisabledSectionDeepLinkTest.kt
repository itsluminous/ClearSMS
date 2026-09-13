package app.clearsms.ui.navigation

import android.content.Intent
import android.net.Uri
import app.clearsms.domain.model.EnabledSections
import app.clearsms.domain.model.StartDestination
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A deep link to a DISABLED section must degrade gracefully: never crash,
 * never land nowhere, never resurrect the hidden screen - and never violate
 * the v0.17.2 invariant (a tab-targeted link is navigated with the bottom
 * bar's own options, so the redirect must stay a tab SELECTION, `selectTab`
 * still true, or the redirected route would be plain-pushed and corrupt the
 * start destination's saved back stack all over again).
 *
 * The behaviour under test is [LaterIntentTriage.resolve]: redirect to the
 * resolved start tab - the same screen a cold start opens - rather than
 * ignoring the link, because a tapped notification that does nothing looks
 * broken. This also covers a notification posted BEFORE the section was
 * disabled and tapped after: the pending intent is unchanged, so it flows
 * through exactly this triage.
 */
@RunWith(RobolectricTestRunner::class)
class DisabledSectionDeepLinkTest {
    private val routeForTab =
        mapOf(
            StartDestination.INBOX to Routes.INBOX,
            StartDestination.FINANCE to Routes.FINANCE,
            StartDestination.ALERTS to Routes.ALERTS,
        )

    /** Every valid flag combination (all-off is healed to all-on at read time). */
    private val combinations =
        listOf(true, false)
            .flatMap { i ->
                listOf(true, false).flatMap { f ->
                    listOf(true, false).map { a -> EnabledSections.from(inbox = i, finance = f, alerts = a) }
                }
            }.distinct()

    @Test
    fun `a tab link to an ENABLED section passes through unchanged in every combination`() {
        for (sections in combinations) {
            for (tab in StartDestination.entries.filter(sections::isEnabled)) {
                val action = LaterIntentAction.Navigate(routeForTab.getValue(tab), selectTab = true)
                assertThat(LaterIntentTriage.resolve(action, sections)).isEqualTo(action)
            }
        }
    }

    @Test
    fun `a tab link to a DISABLED section redirects to the resolved start tab`() {
        for (sections in combinations) {
            for (tab in StartDestination.entries.filterNot(sections::isEnabled)) {
                val action = LaterIntentAction.Navigate(routeForTab.getValue(tab), selectTab = true)
                val resolved = LaterIntentTriage.resolve(action, sections)
                assertThat(resolved.route).isEqualTo(routeForTab.getValue(sections.resolveStart(tab)))
                // The redirect target must itself be an ENABLED tab.
                assertThat(sections.isEnabled(sections.resolveStart(tab))).isTrue()
            }
        }
    }

    @Test
    fun `the redirect stays a tab SELECTION - the v0_17_2 invariant survives the gate`() {
        for (sections in combinations) {
            for (tab in StartDestination.entries.filterNot(sections::isEnabled)) {
                val action = LaterIntentAction.Navigate(routeForTab.getValue(tab), selectTab = true)
                // selectTab=false here would plain-push the redirected route,
                // recreating exactly the saved-state corruption v0.17.2 fixed.
                assertThat(LaterIntentTriage.resolve(action, sections).selectTab).isTrue()
            }
        }
    }

    @Test
    fun `a conversation link with messageId passes through untouched in every combination`() {
        // Inbox OFF included: the conversation screen is section-independent
        // (Search, Finance and Alerts all open it), so an external intent or
        // a stale pre-disable message notification still shows the exact
        // thread - and keeps its ?messageId= highlight.
        val action = LaterIntentAction.Navigate(Routes.conversation(42L, 7L), selectTab = false)
        for (sections in combinations) {
            assertThat(LaterIntentTriage.resolve(action, sections)).isEqualTo(action)
        }
    }

    @Test
    fun `a stale bill-due notification tapped after Alerts was disabled lands on an enabled tab`() {
        // End to end: the EXACT uri ReminderNotifier puts in its content
        // intent, classified and then resolved with Alerts off - the
        // "notification posted before the disable" path.
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("clearsms://alerts"))
        val classified = LaterIntentTriage.classify(intent) as LaterIntentAction.Navigate
        val sections = EnabledSections(inbox = true, finance = true, alerts = false)
        val resolved = LaterIntentTriage.resolve(classified, sections)
        assertThat(resolved.route).isEqualTo(Routes.INBOX)
        assertThat(resolved.selectTab).isTrue()
    }
}
