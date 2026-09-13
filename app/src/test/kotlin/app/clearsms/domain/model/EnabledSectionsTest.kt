package app.clearsms.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The section-visibility contract behind the bottom navigation: which tabs
 * exist for every flag combination, where the app starts, when the bar is
 * shown at all, and the last-enabled guard - all pure logic, no Compose.
 */
class EnabledSectionsTest {
    private fun sections(
        inbox: Boolean,
        finance: Boolean,
        alerts: Boolean,
    ) = EnabledSections.from(inbox = inbox, finance = finance, alerts = alerts)

    @Test
    fun `visible tabs follow the flags in canonical order, for every combination`() {
        assertThat(sections(true, true, true).visibleTabs)
            .containsExactly(StartDestination.INBOX, StartDestination.FINANCE, StartDestination.ALERTS)
            .inOrder()
        assertThat(sections(true, true, false).visibleTabs)
            .containsExactly(StartDestination.INBOX, StartDestination.FINANCE)
            .inOrder()
        assertThat(sections(true, false, true).visibleTabs)
            .containsExactly(StartDestination.INBOX, StartDestination.ALERTS)
            .inOrder()
        assertThat(sections(false, true, true).visibleTabs)
            .containsExactly(StartDestination.FINANCE, StartDestination.ALERTS)
            .inOrder()
        assertThat(sections(true, false, false).visibleTabs).containsExactly(StartDestination.INBOX)
        assertThat(sections(false, true, false).visibleTabs).containsExactly(StartDestination.FINANCE)
        assertThat(sections(false, false, true).visibleTabs).containsExactly(StartDestination.ALERTS)
    }

    @Test
    fun `all-off normalizes to all-on so the tab list is never empty`() {
        // The settings UI cannot produce all-off (the guard refuses), but a
        // hand-edited settings backup can restore it - reads must heal it.
        assertThat(sections(false, false, false)).isEqualTo(EnabledSections())
        assertThat(sections(false, false, false).visibleTabs).hasSize(3)
    }

    @Test
    fun `the bar is shown only with two or more enabled sections`() {
        assertThat(sections(true, true, true).showBottomBar).isTrue()
        assertThat(sections(true, true, false).showBottomBar).isTrue()
        assertThat(sections(true, false, true).showBottomBar).isTrue()
        assertThat(sections(false, true, true).showBottomBar).isTrue()
        // A single-item bar switches nothing - dead chrome, so no bar.
        assertThat(sections(true, false, false).showBottomBar).isFalse()
        assertThat(sections(false, true, false).showBottomBar).isFalse()
        assertThat(sections(false, false, true).showBottomBar).isFalse()
    }

    @Test
    fun `last enabled section can never be disabled, everything else can`() {
        // With all three on, any one may go.
        StartDestination.entries.forEach { tab ->
            assertThat(sections(true, true, true).canDisable(tab)).isTrue()
        }
        // With two on, either survivor may still go.
        assertThat(sections(true, true, false).canDisable(StartDestination.INBOX)).isTrue()
        assertThat(sections(true, true, false).canDisable(StartDestination.FINANCE)).isTrue()
        // With one on, that one is locked; the already-off ones are moot but safe.
        assertThat(sections(true, false, false).canDisable(StartDestination.INBOX)).isFalse()
        assertThat(sections(false, true, false).canDisable(StartDestination.FINANCE)).isFalse()
        assertThat(sections(false, false, true).canDisable(StartDestination.ALERTS)).isFalse()
        assertThat(sections(true, false, false).canDisable(StartDestination.FINANCE)).isTrue()
    }

    @Test
    fun `start destination keeps the preference while enabled, else first enabled tab`() {
        // Preference enabled: honored, whatever else is off.
        assertThat(sections(true, true, true).resolveStart(StartDestination.FINANCE))
            .isEqualTo(StartDestination.FINANCE)
        assertThat(sections(false, true, false).resolveStart(StartDestination.FINANCE))
            .isEqualTo(StartDestination.FINANCE)
        // Preference disabled: first enabled tab in canonical order.
        assertThat(sections(false, true, true).resolveStart(StartDestination.INBOX))
            .isEqualTo(StartDestination.FINANCE)
        assertThat(sections(false, false, true).resolveStart(StartDestination.INBOX))
            .isEqualTo(StartDestination.ALERTS)
        assertThat(sections(true, false, true).resolveStart(StartDestination.FINANCE))
            .isEqualTo(StartDestination.INBOX)
        assertThat(sections(true, true, false).resolveStart(StartDestination.ALERTS))
            .isEqualTo(StartDestination.INBOX)
        // Normalized all-off: resolve never throws and honors the preference.
        assertThat(sections(false, false, false).resolveStart(StartDestination.ALERTS))
            .isEqualTo(StartDestination.ALERTS)
    }
}
