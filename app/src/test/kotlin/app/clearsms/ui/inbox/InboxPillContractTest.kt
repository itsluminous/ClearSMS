package app.clearsms.ui.inbox

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
        // The label is the only thing a rename changes.
        assertThat(inbox).contains("label = { Text(pills.label(pill, InboxPill::defaultLabel)) },")
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
        assertThat(vm).contains("combine(effectiveFilter, contactsTick")
        assertThat(vm).contains("val current = effectiveFilter.first()")
        assertThat(vm).contains("messageRepository.pagedInbox(current.category, current.unreadOnly, current.scamOnly")
        assertThat(vm).contains("messageRepository.inboxThreadIds(current.category, current.unreadOnly, current.scamOnly")
    }

    @Test
    fun `the spam pill is a scam-flag filter in SQL, not a category`() {
        val dao = source("data/db/MessageDao.kt")
        assertThat(dao).contains("AND (:scamOnly = 0 OR m.subCategory = 'SCAM')")
        assertThat(source("domain/model/Category.kt")).doesNotContain("SPAM")
        assertThat(InboxPill.SCAM.category).isNull()
        assertThat(InboxPill.SCAM.defaultLabel()).isEqualTo("Spam")
    }

    @Test
    fun `settings expose visibility, rename and unread-switch rows, and the order dialog uses labels`() {
        val settings = source("ui/settings/SettingsScreen.kt")
        assertThat(settings).contains("SettingsItem.INBOX_VISIBLE_PILLS ->")
        assertThat(settings).contains("SettingsItem.INBOX_PILL_LABELS ->")
        assertThat(settings).contains("SettingsItem.INBOX_UNREAD_TOGGLE ->")
        assertThat(settings).contains("onToggle = viewModel::setInboxUnreadToggle,")
        assertThat(settings).contains("label = { pills.label(it, InboxPill::defaultLabel) },")
        val dialogs = source("ui/settings/InboxPillDialogs.kt")
        // No minimum: the visibility dialog never disables a checkbox.
        assertThat(dialogs).doesNotContain("enabled = ")
        // Renames are keyed by pill identity.
        assertThat(settings).contains("mutableStateMapOf<InboxPill, String>()")
    }

    @Test
    fun `all four preferences are backed up`() {
        val catalog = source("data/backup/SettingsBackupManager.kt")
        assertThat(catalog).contains("SettingsBackupEntry.StringEntry(\"inbox_pill_order\"),")
        assertThat(catalog).contains("SettingsBackupEntry.StringSetEntry(\"inbox_hidden_pills\"),")
        assertThat(catalog).contains("SettingsBackupEntry.StringEntry(\"inbox_pill_labels\"),")
        assertThat(catalog).contains("SettingsBackupEntry.BooleanEntry(\"inbox_unread_toggle\"),")
    }
}
