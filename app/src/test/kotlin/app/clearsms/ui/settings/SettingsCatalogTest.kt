package app.clearsms.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.clearsms.domain.model.EnabledSections
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The settings layout contract: [SettingsSection] declaration order is the
 * top-level screen, [SettingsItem] declaration order is each sub-screen, so
 * these tests pin the exact top-level list, which sections open a
 * sub-screen and which are direct entries, the row order within each
 * section, the complete row inventory (nothing lost, nothing duplicated by
 * the split into sub-screens), that every row belongs to exactly one screen,
 * and the two conditional-visibility rules.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsCatalogTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun title(item: SettingsItem) = context.getString(item.titleRes)

    private fun sectionTitle(section: SettingsSection) = context.getString(section.titleRes)

    private val everythingOn = SettingsVisibility(EnabledSections(), delayedSendEnabled = true)

    @Test
    fun `the top-level screen lists exactly the operator's entries, in order`() {
        // A sub-screen entry shows its section name; a direct entry IS its
        // one row, so it shows the row's title (there is no "Startup",
        // "Rules" or "Signature" header anywhere on screen).
        val topLevel =
            settingsTopLevelEntries().map { entry ->
                when (entry) {
                    is SettingsTopLevelEntry.SubScreen -> sectionTitle(entry.section)
                    is SettingsTopLevelEntry.Direct -> title(entry.item)
                }
            }
        assertThat(topLevel)
            .containsExactly(
                "Messages",
                "Appearance",
                "Notifications",
                "OTP",
                "Inbox",
                "Finance",
                "Alerts",
                "Default screen",
                "Backup & restore",
                "Manage rules",
                "SMS signature",
                "Donate",
                "About",
            ).inOrder()
    }

    @Test
    fun `Default screen, Manage rules and Signature are direct actions, every other entry opens a sub-screen`() {
        val direct = settingsTopLevelEntries().filterIsInstance<SettingsTopLevelEntry.Direct>()
        assertThat(direct.map { it.item })
            .containsExactly(SettingsItem.DEFAULT_SCREEN, SettingsItem.MANAGE_RULES, SettingsItem.SIGNATURE)
            .inOrder()
        assertThat(direct.map { it.section })
            .containsExactly(SettingsSection.STARTUP, SettingsSection.RULES, SettingsSection.SIGNATURE)
            .inOrder()
        assertThat(SettingsSection.entries.filter { it.subScreen })
            .containsExactly(
                SettingsSection.MESSAGES,
                SettingsSection.APPEARANCE,
                SettingsSection.NOTIFICATIONS,
                SettingsSection.OTP,
                SettingsSection.INBOX,
                SettingsSection.FINANCE,
                SettingsSection.ALERTS,
                SettingsSection.BACKUP,
                SettingsSection.DONATE,
                SettingsSection.ABOUT,
            ).inOrder()
        // A direct row is not "nested": a link to it lands on the root screen.
        assertThat(
            SettingsItem.entries
                .filterNot { it.nested }
                .map { it.section }
                .toSet(),
        ).isEqualTo(SettingsSection.entries.filterNot { it.subScreen }.toSet())
    }

    @Test
    fun `a direct-entry section holds exactly one row - the thing rendered in place`() {
        // Two rows behind a direct entry would leave one of them unreachable:
        // there is no sub-screen to show it on.
        for (section in SettingsSection.entries.filterNot { it.subScreen }) {
            assertWithMessage("$section is a direct entry and must hold exactly one row")
                .that(section.items)
                .hasSize(1)
        }
    }

    @Test
    fun `every row belongs to exactly one screen - no orphans, none on two screens`() {
        // Totality: the per-section row lists partition the catalog. A row
        // that no section claims would be invisible; a row two sections
        // claimed would render twice. Both are impossible while this holds.
        val claimed = SettingsSection.entries.flatMap { it.items }
        assertThat(claimed).containsNoDuplicates()
        assertThat(claimed).containsExactlyElementsIn(SettingsItem.entries)
        for (item in SettingsItem.entries) {
            assertWithMessage("$item must be claimed by exactly one section")
                .that(SettingsSection.entries.count { item in it.items })
                .isEqualTo(1)
        }
        // And every screen a row can be on is one the top-level screen lists.
        val screens = settingsTopLevelEntries().map { it.section }
        assertThat(screens).containsExactlyElementsIn(SettingsSection.entries).inOrder()
    }

    @Test
    fun `each section's rows are contiguous so headers render exactly once`() {
        val sectionSequence = SettingsItem.entries.map { it.section }
        val distinctRuns =
            sectionSequence.fold(mutableListOf<SettingsSection>()) { runs, section ->
                if (runs.lastOrNull() != section) runs += section
                runs
            }
        assertThat(distinctRuns).containsNoDuplicates()
        // ...and in the same order the top-level screen lists them, so search
        // results (grouped by section) read top to bottom like Settings does.
        assertThat(distinctRuns).isEqualTo(SettingsSection.entries.toList())
    }

    @Test
    fun `rows within each section appear in the exact target order`() {
        val bySection = SettingsItem.entries.groupBy({ sectionTitle(it.section) }, ::title)
        assertThat(bySection["Messages"])
            .containsExactly(
                "Archived messages",
                "Recycle bin",
                "Block & allow list",
                "Strip accents when sending",
                "Delay before sending",
                "Sending delay",
                "Show extracted message details",
                "Sort messages by",
            ).inOrder()
        assertThat(bySection["Appearance"])
            .containsExactly("Theme", "Dynamic color", "Show logos and contact photos", "Logo background")
            .inOrder()
        assertThat(bySection["Notifications"])
            .containsExactly(
                "SMS delivery reports",
                "Notification action buttons",
                "Transaction notifications",
                // Trailing escape hatch to Android's per-channel settings.
                "Customise notifications",
            ).inOrder()
        assertThat(bySection["OTP"])
            .containsExactly("Auto copy", "Auto delete OTP", "OTP display size", "Clear older OTPs")
            .inOrder()
        assertThat(bySection["Inbox"])
            .containsExactly(
                // The section master switch leads: everything below it is
                // meaningless while the tab is hidden.
                "Show Inbox tab",
                "Pill order",
                // Pill customisation (issue #49), directly under the order
                // it refines: visibility, names, and the Unread switch.
                "Visible pills",
                "Rename pills",
                "Unread switch",
                "Default inbox filter",
                "Swipe right action",
                "Swipe left action",
                // Directly under the two swipe-action rows it refines: the
                // per-row band where a swipe never starts (issue #16).
                "Swipe dead zone",
                "Sort inbox again",
            ).inOrder()
        assertThat(bySection["Finance"])
            .containsExactly("Show Finance tab", "Pill order", "Show balance", "Default Finance filter")
            .inOrder()
        assertThat(bySection["Alerts"]).containsExactly("Show Alerts tab", "Pill order").inOrder()
        assertThat(bySection["Startup"]).containsExactly("Default screen")
        assertThat(bySection["Backup & restore"])
            .containsExactly(
                "Back up messages",
                "Restore messages",
                "Back up settings",
                "Restore settings",
                "Backup frequency",
                // Directly under the frequency it gates: the user-chosen SAF
                // directory both automatic exports land in.
                "Backup location",
            ).inOrder()
        assertThat(bySection["Rules"]).containsExactly("Manage rules")
        assertThat(bySection["Signature"]).containsExactly("SMS signature")
        assertThat(bySection["Donate"]).containsExactly("UPI", "Paypal").inOrder()
        // Permissions, Privacy policy and Licenses moved INSIDE About, after
        // the two rows that were already there.
        assertThat(bySection["About"])
            .containsExactly("Version", "Source code", "Permissions", "Privacy policy", "Open source licenses")
            .inOrder()
    }

    @Test
    fun `Permissions, Privacy policy and Licenses live on the About sub-screen`() {
        for (item in listOf(SettingsItem.PERMISSIONS, SettingsItem.PRIVACY_POLICY, SettingsItem.LICENSES)) {
            assertThat(item.section).isEqualTo(SettingsSection.ABOUT)
            assertThat(item.nested).isTrue()
        }
        assertThat(SettingsSection.ABOUT.items.takeLast(3))
            .containsExactly(SettingsItem.PERMISSIONS, SettingsItem.PRIVACY_POLICY, SettingsItem.LICENSES)
            .inOrder()
    }

    @Test
    fun `row inventory is complete - every pre-reorg row survives and nothing is duplicated`() {
        // The 32 rows that existed before the reorganisation, by title.
        val preReorgRows =
            listOf(
                "Archived messages",
                "Block & allow list",
                "Back up messages",
                "Restore messages",
                "Backup frequency",
                "Theme",
                "Dynamic color",
                "Show logos and contact photos",
                "Logo background",
                "Show extracted message details",
                "Show balance",
                "SMS delivery reports",
                "Notification action buttons",
                "Transaction notifications",
                "Pill order",
                "Pill order",
                "Pill order",
                "Swipe right action",
                "Swipe left action",
                "Default screen",
                "Default inbox filter",
                "Sort inbox again",
                "Auto copy",
                "Auto delete OTP",
                "OTP display size",
                "Clear older OTPs",
                "Manage rules",
                "SMS signature",
                "Version",
                "Permissions",
                "Privacy policy",
                "Open source licenses",
            )
        val newRows =
            listOf(
                "Default Finance filter",
                "Source code",
                "Paypal",
                "UPI",
                "Back up settings",
                "Restore settings",
                // Messages section, right after Archived: the recycle bin
                // toggle + entry point (30-day retention, default OFF).
                "Recycle bin",
                // Backup & restore: the automatic-backup directory row that
                // gates the backup frequency.
                "Backup location",
                // Messages section (GitHub #17): opt-in auto accent folding
                // when it makes a text send as fewer SMS.
                "Strip accents when sending",
                // Messages section (GitHub #40): opt-in delayed sending -
                // the toggle plus the delay it gates.
                "Delay before sending",
                "Sending delay",
                // Inbox: the per-row swipe dead zone editor (issue #16).
                "Swipe dead zone",
                // First row of each tab's section: the master switch that
                // hides the tab and the rest of its settings.
                "Show Inbox tab",
                "Show Finance tab",
                "Show Alerts tab",
                // Notifications: action row that opens Android's own
                // notification settings for the app (per-channel control).
                "Customise notifications",
                // Inbox (GitHub #49): which pills show, what they are
                // called, and whether the Unread switch is rendered.
                "Visible pills",
                "Rename pills",
                "Unread switch",
                // Messages (GitHub #45): sort by sent vs received time.
                "Sort messages by",
            )
        val allTitles = SettingsItem.entries.map(::title)

        // No row lost, none dropped: 32 survivors + 20 additions = 52 rows.
        // The split into sub-screens moved rows; it added and removed none.
        assertThat(allTitles.sorted()).isEqualTo((preReorgRows + newRows).sorted())
        // No duplicates: "Pill order" legitimately appears once per pills
        // screen (Inbox / Finance / Alerts); every other (section, title)
        // pair is unique.
        val identity = SettingsItem.entries.map { it.section to title(it) }
        assertThat(identity).containsNoDuplicates()
    }

    private fun search(query: String) = filterSettingsRows(SettingsItem.entries, query, ::title) { "" }

    @Test
    fun `customise notifications trails the Notifications section and is searchable`() {
        val notificationRows = SettingsItem.entries.filter { it.section == SettingsSection.NOTIFICATIONS }
        assertThat(notificationRows.last()).isEqualTo(SettingsItem.SYSTEM_NOTIFICATION_SETTINGS)
        assertThat(search("customise notifications")).contains(SettingsItem.SYSTEM_NOTIFICATION_SETTINGS)
    }

    @Test
    fun `search finds a row in the Messages section`() {
        assertThat(search("block allow").map { it.section }).containsExactly(SettingsSection.MESSAGES)
        assertThat(search("archived")).contains(SettingsItem.ARCHIVED)
    }

    @Test
    fun `recycle bin row sits in Messages directly after Archived and is searchable`() {
        val messagesRows = SettingsItem.entries.filter { it.section == SettingsSection.MESSAGES }
        assertThat(messagesRows.indexOf(SettingsItem.RECYCLE_BIN))
            .isEqualTo(messagesRows.indexOf(SettingsItem.ARCHIVED) + 1)
        assertThat(search("recycle bin")).containsExactly(SettingsItem.RECYCLE_BIN)
    }

    @Test
    fun `backup location row sits directly after Backup frequency and is searchable`() {
        val backupRows = SettingsItem.entries.filter { it.section == SettingsSection.BACKUP }
        assertThat(backupRows.indexOf(SettingsItem.BACKUP_LOCATION))
            .isEqualTo(backupRows.indexOf(SettingsItem.BACKUP_FREQUENCY) + 1)
        assertThat(search("backup location")).containsExactly(SettingsItem.BACKUP_LOCATION)
    }

    @Test
    fun `search finds rows in the Donate section`() {
        assertThat(search("paypal")).containsExactly(SettingsItem.PAYPAL)
        assertThat(search("upi")).contains(SettingsItem.UPI)
    }

    @Test
    fun `search finds every About row, including the three that moved in`() {
        assertThat(search("source code")).containsExactly(SettingsItem.SOURCE_CODE)
        assertThat(search("permissions")).contains(SettingsItem.PERMISSIONS)
        assertThat(search("privacy")).contains(SettingsItem.PRIVACY_POLICY)
        assertThat(search("licenses")).contains(SettingsItem.LICENSES)
    }

    @Test
    fun `every row is reachable by searching its own title`() {
        SettingsItem.entries.forEach { item ->
            assertThat(search(title(item))).contains(item)
        }
    }

    @Test
    fun `all sections enabled and delayed sending on shows every row`() {
        assertThat(visibleSettingsItems(everythingOn)).isEqualTo(SettingsItem.entries.toList())
    }

    @Test
    fun `the sending-delay row is hidden while delayed sending is off, present when on`() {
        // Meaningless while the toggle is off - same rule as a disabled
        // section's child rows. The toggle itself always stays.
        val off = visibleSettingsItems(everythingOn.copy(delayedSendEnabled = false))
        assertThat(off).doesNotContain(SettingsItem.DELAYED_SEND_DELAY)
        assertThat(off).contains(SettingsItem.DELAYED_SEND)
        // Nothing else is affected by the toggle.
        assertThat(off).isEqualTo(SettingsItem.entries - SettingsItem.DELAYED_SEND_DELAY)

        val on = visibleSettingsItems(everythingOn.copy(delayedSendEnabled = true))
        assertThat(on).contains(SettingsItem.DELAYED_SEND_DELAY)
        assertThat(on.indexOf(SettingsItem.DELAYED_SEND_DELAY)).isEqualTo(on.indexOf(SettingsItem.DELAYED_SEND) + 1)
    }

    @Test
    fun `a disabled section keeps only its master switch, other sections untouched`() {
        val visible =
            visibleSettingsItems(everythingOn.copy(sections = EnabledSections(inbox = true, finance = false, alerts = true)))
        // Finance collapses to the one row that can bring it back - so its
        // sub-screen stays listed and reachable, showing just that toggle.
        assertThat(visible.filter { it.section == SettingsSection.FINANCE })
            .containsExactly(SettingsItem.SHOW_FINANCE_TAB)
        // ...and nothing outside Finance is affected.
        assertThat(visible.filter { it.section != SettingsSection.FINANCE })
            .isEqualTo(SettingsItem.entries.filter { it.section != SettingsSection.FINANCE })
        // The section's top-level entry never disappears: the way back must stay a tap away.
        assertThat(settingsTopLevelEntries().map { it.section }).contains(SettingsSection.FINANCE)
    }

    @Test
    fun `each disabled section hides its own child rows and only those`() {
        val onlyAlerts =
            visibleSettingsItems(everythingOn.copy(sections = EnabledSections(inbox = false, finance = false, alerts = true)))
        assertThat(onlyAlerts.filter { it.section == SettingsSection.INBOX })
            .containsExactly(SettingsItem.SHOW_INBOX_TAB)
        assertThat(onlyAlerts.filter { it.section == SettingsSection.FINANCE })
            .containsExactly(SettingsItem.SHOW_FINANCE_TAB)
        assertThat(onlyAlerts.filter { it.section == SettingsSection.ALERTS })
            .isEqualTo(SettingsItem.entries.filter { it.section == SettingsSection.ALERTS })
        // The master switches always survive - they are the way back.
        assertThat(onlyAlerts).containsAtLeast(
            SettingsItem.SHOW_INBOX_TAB,
            SettingsItem.SHOW_FINANCE_TAB,
            SettingsItem.SHOW_ALERTS_TAB,
        )
    }
}
