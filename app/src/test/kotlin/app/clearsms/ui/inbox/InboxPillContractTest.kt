package app.clearsms.ui.inbox

import app.clearsms.domain.model.Category
import app.clearsms.domain.model.InboxPill
import app.clearsms.ui.components.defaultLabel
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Source-level contracts for the pill customisation (issue #49), in the
 * repo's convention (no Compose UI harness): the inbox renders the VISIBLE
 * pills by identity, drops the row when all are hidden, guards the Unread
 * switch behind its setting, and the Settings rows exist and are backed up.
 */
class InboxPillContractTest {
    private fun source(path: String) = File("src/main/kotlin/app/clearsms/$path").readText()

    @Test
    fun `pill row renders the visible pills, keyed and selected by identity not label`() {
        val inbox = source("ui/inbox/InboxScreen.kt")
        assertThat(inbox).contains("items(pills.visible, key = { it.name })")
        assertThat(inbox).contains("selected = filter.pill == pill,")
        assertThat(inbox).contains("onClick = { onSelectPill(pill) },")
        // Built-in names only: renaming was dropped before it shipped.
        assertThat(inbox).contains("label = { Text(pill.defaultLabel()) },")
        assertThat(inbox).doesNotContain("Category.entries.toList()")
    }

    @Test
    fun `all pills hidden means no pill row at all`() {
        val inbox = source("ui/inbox/InboxScreen.kt")
        val guard = inbox.indexOf("if (state.pills.showsRow) {")
        val row = inbox.indexOf("item(key = \"filters\")")
        assertThat(guard).isGreaterThan(-1)
        assertThat(row).isGreaterThan(guard)
    }

    @Test
    fun `unread switch is rendered only while its setting is on`() {
        val inbox = source("ui/inbox/InboxScreen.kt")
        // Off = the shared title gets no trailing content at all.
        val guard = inbox.indexOf("if (state.showUnreadToggle) {")
        val toggle = inbox.indexOf("UnreadSwitch(")
        assertThat(guard).isGreaterThan(-1)
        assertThat(toggle).isGreaterThan(guard)
        assertThat(inbox).contains("expandedTrailing =")
        // Hiding the switch must not touch how counts are derived.
        val vm = source("ui/inbox/InboxViewModel.kt")
        assertThat(vm).contains("totalUnread = counts.sumOf { it.count },")
        assertThat(vm).contains("unreadCounts = counts.associate { it.category to it.count },")
        assertThat(vm).contains("settings.inboxUnreadToggle")
    }

    @Test
    fun `queries and select-all run on the filter constrained to visible pills`() {
        val vm = source("ui/inbox/InboxViewModel.kt")
        assertThat(vm).contains("current.constrainedTo(config.visible, unreadControl = unreadShown)")
        // The pager's query key is derived from the effective filter (see InboxPagerContractTest).
        assertThat(vm).contains("inboxPagerKeys(effectiveFilter, settings.messageSortOrder)")
        assertThat(vm).contains("val current = effectiveFilter.first()")
        assertThat(vm).contains(".pagedInbox(key.category, key.unreadOnly, key.sortOrder)")
        assertThat(vm).contains("messageRepository.inboxThreadIds(current.category, current.unreadOnly)")
    }

    @Test
    fun `the spam pill is a real category, and the DAO has no scam-flag filter left`() {
        val dao = source("data/db/MessageDao.kt")
        assertThat(dao).doesNotContain("scamOnly")
        assertThat(dao).doesNotContain("m.subCategory = 'SCAM'")
        assertThat(InboxPill.SPAM.category).isEqualTo(Category.SPAM)
        assertThat(InboxPill.SPAM.defaultLabel()).isEqualTo("Spam")
        // One pill per category, one category per pill.
        assertThat(InboxPill.entries.map { it.category }).containsExactlyElementsIn(Category.entries)
    }

    @Test
    fun `settings expose visibility and unread-switch rows, and no rename row`() {
        val settings = source("ui/settings/SettingsScreen.kt")
        assertThat(settings).contains("SettingsItem.INBOX_VISIBLE_PILLS ->")
        assertThat(settings).contains("SettingsItem.INBOX_UNREAD_TOGGLE ->")
        assertThat(settings).contains("onToggle = viewModel::setInboxUnreadToggle,")
        // Both Inbox pill dialogs show the built-in names.
        assertThat(settings).contains("label = { it.defaultLabel() },")
        val dialogs = source("ui/settings/InboxPillDialogs.kt")
        // No minimum: the visibility dialog never disables a checkbox.
        assertThat(dialogs).doesNotContain("enabled = ")
        // Renaming pills was dropped before release: no row, no dialog.
        assertThat(settings).doesNotContain("PILL_LABELS")
        assertThat(settings).doesNotContain("mutableStateMapOf<InboxPill, String>()")
    }

    @Test
    fun `all three preferences are backed up, the dropped labels key is not`() {
        val catalog = source("data/backup/SettingsBackupManager.kt")
        assertThat(catalog).contains("SettingsBackupEntry.StringEntry(\"inbox_pill_order\"),")
        assertThat(catalog).contains("SettingsBackupEntry.StringSetEntry(\"inbox_hidden_pills\"),")
        assertThat(catalog).contains("SettingsBackupEntry.BooleanEntry(\"inbox_unread_toggle\"),")
        assertThat(catalog).doesNotContain("inbox_pill_labels")
    }
}
