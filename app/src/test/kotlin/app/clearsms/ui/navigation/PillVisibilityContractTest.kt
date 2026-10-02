package app.clearsms.ui.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Source-level contract, in the repo's convention (no Compose UI harness):
 * the Inbox, Finance and Alerts chip rows share ONE pill-visibility
 * mechanism - [PillConfig] for order + hidden set, [activePill] for the
 * "a hidden chip can never stay active" guard, one settings decoder, one
 * visibility dialog - so the three screens cannot drift apart.
 */
class PillVisibilityContractTest {
    private fun source(path: String) = File("src/main/kotlin/app/clearsms/$path").readText()

    @Test
    fun `all three screens resolve visibility through PillConfig, none re-implements it`() {
        val inbox = source("ui/inbox/InboxPillConfig.kt")
        val finance = source("ui/finance/FinanceViewModel.kt")
        val alerts = source("ui/alerts/AlertsViewModel.kt")
        assertThat(inbox).contains("PillConfig(InboxPill.entries.toList(), order, hidden)")
        assertThat(finance).contains("PillConfig(FinanceTab.entries.toList(), order, hidden)")
        assertThat(alerts).contains("PillConfig(AlertFilter.entries.toList(), order, hidden)")
        // No second implementation of the hidden-set filter anywhere.
        for (text in listOf(inbox, finance, alerts)) {
            assertThat(text).doesNotContain("filterNot { it in hidden }")
            assertThat(text).doesNotContain("orderedPills(")
        }
        // The one place the rule lives.
        assertThat(source("ui/navigation/PillVisibility.kt")).contains("val visible: List<T> = ordered.filterNot { it in hidden }")
    }

    @Test
    fun `all three screens apply the shared activePill guard to their selection`() {
        val inboxGuard = source("ui/inbox/InboxViewModel.kt")
        assertThat(inboxGuard).contains("pill = activePill(pill, visible, fallback = null),")
        assertThat(inboxGuard).contains("current.constrainedTo(config.visible, unreadControl = unreadShown)")

        val finance = source("ui/finance/FinanceViewModel.kt")
        assertThat(finance).contains(
            "activePill(override ?: default, config.visible, fallback = config.visible.firstOrNull())",
        )
        assertThat(finance).contains("val selectedTab: StateFlow<FinanceTab?>")

        val alerts = source("ui/alerts/AlertsViewModel.kt")
        assertThat(alerts).contains("activePill(current, config.visible, fallback = AlertFilter.ALL) to config")
        // The list filters on the GUARDED value, never the raw selection.
        assertThat(alerts).contains("upcoming = upcoming.filter { currentFilter.matches(it.type) },")
    }

    @Test
    fun `every row renders the visible pills by identity and disappears when all are hidden`() {
        val inbox = source("ui/inbox/InboxScreen.kt")
        assertThat(inbox).contains("if (state.pills.showsRow) {")
        assertThat(inbox).contains("items(pills.visible, key = { it.name })")

        val finance = source("ui/finance/FinanceScreen.kt")
        assertThat(finance.indexOf("if (state.pills.showsRow) {")).isGreaterThan(-1)
        assertThat(finance.indexOf("item(key = \"pills\")")).isGreaterThan(finance.indexOf("if (state.pills.showsRow) {"))
        assertThat(finance).contains("items(pills.visible, key = { it.name }) { tab ->")
        // No section at all once every tab is hidden - the summary stands alone.
        assertThat(finance).containsMatch("""null ->\s*\{?\s*Unit""")
        assertThat(finance).doesNotContain("FinanceTab.entries.toList()), key")

        val alerts = source("ui/alerts/AlertsScreen.kt")
        assertThat(alerts.indexOf("if (state.pills.showsRow) {")).isGreaterThan(-1)
        assertThat(alerts.indexOf("item(key = \"chips\")")).isGreaterThan(alerts.indexOf("if (state.pills.showsRow) {"))
        assertThat(alerts).contains("items(state.pills.visible, key = { it.name }) { option ->")
        assertThat(alerts).doesNotContain("AlertFilter.entries.toList()), key")
    }

    @Test
    fun `one settings decoder, one dialog, one summary serve the three hidden sets`() {
        val impl = source("data/prefs/SettingsRepositoryImpl.kt")
        assertThat(impl).contains("it[KEY_INBOX_HIDDEN_PILLS].toHiddenPills()")
        assertThat(impl).contains("it[KEY_FINANCE_HIDDEN_PILLS].toHiddenPills()")
        assertThat(impl).contains("it[KEY_ALERTS_HIDDEN_PILLS].toHiddenPills()")
        assertThat(impl).contains("stringSetPreferencesKey(\"finance_hidden_pills\")")
        assertThat(impl).contains("stringSetPreferencesKey(\"alerts_hidden_pills\")")

        val settings = source("ui/settings/SettingsScreen.kt")
        for (screen in listOf("INBOX", "FINANCE", "ALERTS")) {
            assertThat(settings).contains("SettingsItem.${screen}_VISIBLE_PILLS -> {")
            assertThat(settings).contains("SettingsDialog.${screen}_VISIBLE_PILLS -> {")
        }
        assertThat(settings.split("VisiblePillsDialog(").size - 1).isEqualTo(3)
        assertThat(settings).contains("private fun <T> visiblePillsSummary(pills: PillConfig<T>): String")
        // No minimum on any screen: the shared dialog never disables a checkbox.
        val dialog = source("ui/settings/InboxPillDialogs.kt")
        assertThat(dialog).contains("fun <T> VisiblePillsDialog(")
        assertThat(dialog).doesNotContain("enabled = ")
        // Hiding and ordering only: renaming pills was dropped before it
        // shipped, on every screen - all chips keep their built-in names.
        assertThat(settings).doesNotContain("PILL_LABELS")
    }

    @Test
    fun `the two new hidden sets are backed up beside the inbox's`() {
        val catalog = source("data/backup/SettingsBackupManager.kt")
        assertThat(catalog).contains("SettingsBackupEntry.StringSetEntry(\"inbox_hidden_pills\"),")
        assertThat(catalog).contains("SettingsBackupEntry.StringSetEntry(\"finance_hidden_pills\"),")
        assertThat(catalog).contains("SettingsBackupEntry.StringSetEntry(\"alerts_hidden_pills\"),")
    }
}
