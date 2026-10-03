package app.clearsms.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.clearsms.R
import app.clearsms.domain.model.EnabledSections
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

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
                // Renamed from "Donate": it now also holds a free way to help.
                "Support",
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
                // The gentler sibling of the block list, directly under it.
                "Muted senders",
                // Launcher shortcuts (GitHub #81), right after the two lists
                // that decide what the launcher may ever be shown.
                "Conversations in app shortcuts",
                "Strip accents when sending",
                "Delay before sending",
                "Sending delay",
                "Show extracted message details",
                "Sort messages by",
                "Message action order",
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
                // it refines: visibility and the Unread switch.
                "Visible pills",
                "Unread switch",
                "Default inbox filter",
                "Swipe right action",
                "Swipe left action",
                // Directly under the two swipe-action rows it refines: the
                // per-row band where a swipe never starts (issue #16).
                "Swipe dead zone",
                "Sort inbox again",
            ).inOrder()
        // Finance and Alerts get the Inbox's pill visibility, right under
        // the pill order it refines - the same presentation on all three.
        assertThat(bySection["Finance"])
            .containsExactly("Show Finance tab", "Pill order", "Visible pills", "Show balance", "Default Finance filter", "Currency")
            .inOrder()
        assertThat(bySection["Alerts"]).containsExactly("Show Alerts tab", "Pill order", "Visible pills").inOrder()
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
        // The CATALOG keeps all three rows (PayPal is hidden at render time by
        // PAYPAL_DONATION_ENABLED, see the Support tests below); the free
        // GitHub star trails the two payment rows.
        assertThat(bySection["Support"]).containsExactly("UPI", "Paypal", "Star on GitHub").inOrder()
        // Permissions, Privacy policy and Licenses moved INSIDE About, after
        // the two rows that were already there; the diagnostic-log report
        // sits between them, next to Source code (the other row about the
        // app's own build rather than its legal/permission footing).
        assertThat(bySection["About"])
            .containsExactly(
                "Version",
                "Source code",
                "Share diagnostic logs",
                "Permissions",
                "Privacy policy",
                "Open source licenses",
            ).inOrder()
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
                // Inbox (GitHub #49): which pills show and whether the
                // Unread switch is rendered. (Renaming pills was tried on a
                // branch and dropped before release.)
                "Visible pills",
                "Unread switch",
                // Messages (GitHub #45): sort by sent vs received time.
                "Sort messages by",
                // Messages (GitHub #61): order of the selection-bar actions.
                "Message action order",
                // Finance and Alerts: the same pill visibility the Inbox has.
                "Visible pills",
                "Visible pills",
                // About: preview + share the in-app diagnostic log report.
                "Share diagnostic logs",
                // Messages: per-sender notification mute (messages arrive,
                // nothing notifies), managed like the block list.
                "Muted senders",
                // Support (ex-Donate): the free way to help, opening the repo.
                "Star on GitHub",
                // Finance (issue #65): the manual currency override - the
                // safety net when detection from the message / SIM is wrong.
                "Currency",
                // Messages (GitHub #81): pinned + recent conversations as
                // launcher shortcuts, with the honest privacy wording.
                "Conversations in app shortcuts",
            )
        val allTitles = SettingsItem.entries.map(::title)

        // No row lost, none dropped: 32 survivors + 26 additions = 58 rows.
        // The split into sub-screens moved rows; it added and removed none.
        assertThat(allTitles.sorted()).isEqualTo((preReorgRows + newRows).sorted())
        // No duplicates: "Pill order" and "Visible pills" legitimately appear
        // once per pills screen (Inbox / Finance / Alerts); every other
        // (section, title) pair is unique.
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
    fun `muted senders row sits in Messages directly after the block list and is searchable`() {
        val messagesRows = SettingsItem.entries.filter { it.section == SettingsSection.MESSAGES }
        assertThat(messagesRows.indexOf(SettingsItem.MUTED_SENDERS))
            .isEqualTo(messagesRows.indexOf(SettingsItem.BLOCK_LIST) + 1)
        assertThat(search("muted")).containsExactly(SettingsItem.MUTED_SENDERS)
    }

    @Test
    fun `conversation shortcuts follows the muted senders row and is searchable`() {
        val messagesRows = SettingsItem.entries.filter { it.section == SettingsSection.MESSAGES }
        assertThat(messagesRows.indexOf(SettingsItem.CONVERSATION_SHORTCUTS))
            .isEqualTo(messagesRows.indexOf(SettingsItem.MUTED_SENDERS) + 1)
        assertThat(search("shortcuts")).containsExactly(SettingsItem.CONVERSATION_SHORTCUTS)
        assertThat(search("app shortcuts")).containsExactly(SettingsItem.CONVERSATION_SHORTCUTS)
    }

    @Test
    fun `message action order row trails Messages after Sort messages by and is searchable`() {
        // Issue #61: ordering the conversation selection-bar actions is a
        // conversation-view preference, so it sits with "Show extracted
        // message details" and "Sort messages by", not in Inbox.
        val messagesRows = SettingsItem.entries.filter { it.section == SettingsSection.MESSAGES }
        assertThat(messagesRows.indexOf(SettingsItem.SELECTION_ACTION_ORDER))
            .isEqualTo(messagesRows.indexOf(SettingsItem.MESSAGE_SORT_ORDER) + 1)
        assertThat(messagesRows.last()).isEqualTo(SettingsItem.SELECTION_ACTION_ORDER)
        assertThat(search("message action order")).containsExactly(SettingsItem.SELECTION_ACTION_ORDER)
        assertThat(search("action order")).contains(SettingsItem.SELECTION_ACTION_ORDER)
    }

    @Test
    fun `backup location row sits directly after Backup frequency and is searchable`() {
        val backupRows = SettingsItem.entries.filter { it.section == SettingsSection.BACKUP }
        assertThat(backupRows.indexOf(SettingsItem.BACKUP_LOCATION))
            .isEqualTo(backupRows.indexOf(SettingsItem.BACKUP_FREQUENCY) + 1)
        assertThat(search("backup location")).containsExactly(SettingsItem.BACKUP_LOCATION)
    }

    @Test
    fun `search over the catalog finds every Support row`() {
        assertThat(search("paypal")).containsExactly(SettingsItem.PAYPAL)
        assertThat(search("upi")).contains(SettingsItem.UPI)
        assertThat(search("star github")).containsExactly(SettingsItem.STAR_ON_GITHUB)
    }

    // ---- Support section (renamed from Donate) ---------------------------

    private fun supportRows(paypalEnabled: Boolean = PAYPAL_DONATION_ENABLED) =
        visibleSettingsItems(everythingOn, paypalEnabled).filter { it.section == SettingsSection.DONATE }

    @Test
    fun `the section is labelled Support, keeps the DONATE route identity, and nothing still says Donate`() {
        assertThat(sectionTitle(SettingsSection.DONATE)).isEqualTo("Support")
        // The enum name is the sub-screen route segment and the highlight
        // target; existing deep links must keep working after the rename.
        assertThat(SettingsSection.DONATE.name).isEqualTo("DONATE")
        assertThat(SettingsSection.DONATE.subScreen).isTrue()
        // No user-visible string anywhere still carries the old label.
        val strings = File(listOf("src/main/res/values", "app/src/main/res/values").first { File(it).isDirectory })
        val values = strings.listFiles { f -> f.extension == "xml" }!!.map { it.readText() }
        for (xml in values) {
            assertThat(xml).doesNotContain(">Donate<")
        }
        assertThat(SettingsSection.entries.map(::sectionTitle)).doesNotContain("Donate")
    }

    @Test
    fun `Support shows UPI and Star on GitHub and NOT PayPal while the flag is off`() {
        // The shipped state: the constant is off, so the default argument
        // hides PayPal without any user-visible explanation.
        assertThat(PAYPAL_DONATION_ENABLED).isFalse()
        assertThat(supportRows()).containsExactly(SettingsItem.UPI, SettingsItem.STAR_ON_GITHUB).inOrder()
        assertThat(supportRows(paypalEnabled = false)).doesNotContain(SettingsItem.PAYPAL)
        // The section itself still has two rows, so its sub-screen is never empty.
        assertThat(supportRows()).hasSize(2)
    }

    @Test
    fun `flipping the PayPal flag back restores the row between UPI and Star on GitHub`() {
        // Re-enabling is a one-line change of PAYPAL_DONATION_ENABLED; this
        // pins that the disabled path has not rotted (row, string, URL intact).
        assertThat(supportRows(paypalEnabled = true))
            .containsExactly(SettingsItem.UPI, SettingsItem.PAYPAL, SettingsItem.STAR_ON_GITHUB)
            .inOrder()
        assertThat(title(SettingsItem.PAYPAL)).isEqualTo("Paypal")
        assertThat(context.getString(R.string.url_donate_paypal)).startsWith("https://paypal.me/")
    }

    @Test
    fun `the PayPal flag touches nothing outside the Support section`() {
        val off = visibleSettingsItems(everythingOn, paypalEnabled = false)
        val on = visibleSettingsItems(everythingOn, paypalEnabled = true)
        assertThat(on - off).containsExactly(SettingsItem.PAYPAL)
        assertThat(off.filter { it.section != SettingsSection.DONATE })
            .isEqualTo(on.filter { it.section != SettingsSection.DONATE })
    }

    @Test
    fun `a hidden PayPal row is also absent from search over the rendered rows`() {
        // The screen searches the rows it renders (visibleSettingsItems), so
        // a hidden row cannot be surfaced by typing its name either.
        val rendered = visibleSettingsItems(everythingOn)
        assertThat(filterSettingsRows(rendered, "paypal", ::title) { "" }).isEmpty()
        assertThat(filterSettingsRows(rendered, "star on github", ::title) { "" })
            .containsExactly(SettingsItem.STAR_ON_GITHUB)
        assertThat(filterSettingsRows(visibleSettingsItems(everythingOn, paypalEnabled = true), "paypal", ::title) { "" })
            .containsExactly(SettingsItem.PAYPAL)
    }

    @Test
    fun `Star on GitHub opens the repository through the SAME url_source_code string as Source code`() {
        assertThat(SettingsItem.STAR_ON_GITHUB.section).isEqualTo(SettingsSection.DONATE)
        assertThat(SettingsItem.STAR_ON_GITHUB.nested).isTrue()
        assertThat(title(SettingsItem.STAR_ON_GITHUB)).isEqualTo("Star on GitHub")
        // The summary says why starring helps rather than just repeating the title.
        val summary = context.getString(R.string.settings_star_on_github_summary)
        assertThat(summary).contains("help")
        assertThat(summary).isNotEqualTo(title(SettingsItem.STAR_ON_GITHUB))
        // One URL, shared with About > Source code, so the two can never drift.
        val screen =
            File(
                listOf("src/main/kotlin/app/clearsms", "app/src/main/kotlin/app/clearsms").first { File(it).isDirectory },
                "ui/settings/SettingsScreen.kt",
            ).readText()
        val starBranch = screen.substringAfter("SettingsItem.STAR_ON_GITHUB ->").substringBefore("SettingsItem.PERMISSIONS ->")
        assertThat(starBranch).contains("R.string.url_source_code")
        assertThat(starBranch).contains("onOpenLink(url)")
        assertThat(starBranch).doesNotContain("https://")
        val sourceBranch = screen.substringAfter("SettingsItem.SOURCE_CODE ->").substringBefore("SettingsItem.SHARE_LOGS ->")
        assertThat(sourceBranch).contains("R.string.url_source_code")
        // The resource is the repository, and it resolves through ExternalLinks
        // like every other link row (snackbar, not crash, when nothing handles it).
        assertThat(context.getString(R.string.url_source_code)).isEqualTo("https://github.com/itsluminous/ClearSMS")
        assertThat(context.resources.getIdentifier("url_star_on_github", "string", context.packageName)).isEqualTo(0)
    }

    @Test
    fun `share diagnostic logs lives on the About sub-screen after Source code and is searchable`() {
        assertThat(SettingsItem.SHARE_LOGS.section).isEqualTo(SettingsSection.ABOUT)
        assertThat(SettingsItem.SHARE_LOGS.nested).isTrue()
        val aboutRows = SettingsSection.ABOUT.items
        assertThat(aboutRows.indexOf(SettingsItem.SHARE_LOGS)).isEqualTo(aboutRows.indexOf(SettingsItem.SOURCE_CODE) + 1)
        assertThat(search("diagnostic logs")).containsExactly(SettingsItem.SHARE_LOGS)
    }

    @Test
    fun `search finds every About row, including the three that moved in`() {
        assertThat(search("source code")).containsExactly(SettingsItem.SOURCE_CODE)
        assertThat(search("diagnostic")).containsExactly(SettingsItem.SHARE_LOGS)
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
    fun `all sections enabled and delayed sending on shows every row - bar the flagged-off PayPal`() {
        assertThat(visibleSettingsItems(everythingOn, paypalEnabled = true)).isEqualTo(SettingsItem.entries.toList())
        assertThat(visibleSettingsItems(everythingOn)).isEqualTo(SettingsItem.entries - SettingsItem.PAYPAL)
    }

    @Test
    fun `the sending-delay row is hidden while delayed sending is off, present when on`() {
        // Meaningless while the toggle is off - same rule as a disabled
        // section's child rows. The toggle itself always stays.
        val off = visibleSettingsItems(everythingOn.copy(delayedSendEnabled = false), paypalEnabled = true)
        assertThat(off).doesNotContain(SettingsItem.DELAYED_SEND_DELAY)
        assertThat(off).contains(SettingsItem.DELAYED_SEND)
        // Nothing else is affected by the toggle.
        assertThat(off).isEqualTo(SettingsItem.entries - SettingsItem.DELAYED_SEND_DELAY)

        val on = visibleSettingsItems(everythingOn.copy(delayedSendEnabled = true), paypalEnabled = true)
        assertThat(on).contains(SettingsItem.DELAYED_SEND_DELAY)
        assertThat(on.indexOf(SettingsItem.DELAYED_SEND_DELAY)).isEqualTo(on.indexOf(SettingsItem.DELAYED_SEND) + 1)
    }

    @Test
    fun `a disabled section keeps only its master switch, other sections untouched`() {
        val visible =
            visibleSettingsItems(
                everythingOn.copy(sections = EnabledSections(inbox = true, finance = false, alerts = true)),
                paypalEnabled = true,
            )
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
            visibleSettingsItems(
                everythingOn.copy(sections = EnabledSections(inbox = false, finance = false, alerts = true)),
                paypalEnabled = true,
            )
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

    // ---- Top-level icons ---------------------------------------------------

    private val sourceRoot =
        File(listOf("src/main/kotlin/app/clearsms", "app/src/main/kotlin/app/clearsms").first { File(it).isDirectory })

    @Test
    fun `every top-level entry leads with a simple, relevant Material outlined icon - pinned per row`() {
        // The icon is catalog data on the section (a non-null constructor
        // argument, so a new section cannot compile without one), and the
        // top-level entry exposes its section's icon - both the sub-screen
        // openers and the three direct rows.
        val byTitle =
            settingsTopLevelEntries().associate { entry ->
                val label =
                    when (entry) {
                        is SettingsTopLevelEntry.SubScreen -> sectionTitle(entry.section)
                        is SettingsTopLevelEntry.Direct -> title(entry.item)
                    }
                assertThat(entry.icon).isEqualTo(entry.section.icon)
                label to entry.icon.name
            }
        assertThat(byTitle)
            .containsExactly(
                "Messages",
                "AutoMirrored.Outlined.Chat",
                "Appearance",
                "Outlined.Palette",
                "Notifications",
                "Outlined.Notifications",
                // The PIN-code glyph, not a push pin.
                "OTP",
                "Outlined.Pin",
                "Inbox",
                "Outlined.Inbox",
                // Matches the Finance tab in the bottom bar.
                "Finance",
                "Outlined.AccountBalanceWallet",
                // Alerts are bill due dates: a calendar, not a second bell.
                "Alerts",
                "Outlined.Event",
                "Default screen",
                "Outlined.Home",
                // Local SAF backups, so the restore arrow rather than a cloud.
                "Backup & restore",
                "Outlined.SettingsBackupRestore",
                "Manage rules",
                "AutoMirrored.Outlined.Rule",
                "SMS signature",
                "Outlined.Draw",
                "Support",
                "Outlined.FavoriteBorder",
                "About",
                "Outlined.Info",
            )
        assertThat(byTitle).hasSize(13)
    }

    @Test
    fun `top-level icons are distinct and all from the outlined set`() {
        val icons = SettingsSection.entries.map { it.icon }
        // Two rows sharing a glyph would look like a copy-paste mistake.
        assertThat(icons).containsNoDuplicates()
        assertThat(icons.map { it.name }).containsNoDuplicates()
        // One visual weight across the screen: outlined only (plain or
        // auto-mirrored), never a filled/rounded/sharp variant.
        for (icon in icons) {
            assertWithMessage("${icon.name} must be an outlined Material icon")
                .that(icon.name)
                .matches("^(AutoMirrored\\.)?Outlined\\.[A-Za-z]+$")
        }
    }

    @Test
    fun `sub-screen rows carry no icon - the request was for the main settings page only`() {
        // SettingsItem (the rows inside sub-screens) has no icon property at
        // all, so no sub-screen row can grow one by accident.
        assertThat(SettingsItem::class.java.methods.map { it.name }).doesNotContain("getIcon")
        assertThat(SettingsSection::class.java.methods.map { it.name }).contains("getIcon")
        val catalog = File(sourceRoot, "ui/settings/SettingsCatalog.kt").readText()
        val itemEnum = catalog.substringAfter("enum class SettingsItem(").substringBefore("data class SettingsVisibility")
        assertThat(itemEnum).doesNotContain("ImageVector")
        assertThat(itemEnum).doesNotContain("Icons.")
    }

    @Test
    fun `the screen takes every top-level icon from the catalog and renders it decoratively`() {
        val screen = File(sourceRoot, "ui/settings/SettingsScreen.kt").readText()
        val catalog = File(sourceRoot, "ui/settings/SettingsCatalog.kt").readText()

        // The screen never names a section icon itself: none of the glyphs
        // the catalog imports for its sections appear in the screen source,
        // and there is no per-section `when` choosing an icon.
        val sectionIconImports =
            Regex("^import androidx\\.compose\\.material\\.icons\\.(?:automirrored\\.)?outlined\\.(\\w+)$", RegexOption.MULTILINE)
                .findAll(catalog)
                .map { it.groupValues[1] }
                .toList()
        assertThat(sectionIconImports).hasSize(SettingsSection.entries.size)
        for (glyph in sectionIconImports) {
            assertWithMessage("SettingsScreen must not hardcode Icons.*.Outlined.$glyph - the catalog owns it")
                .that(screen)
                .doesNotContainMatch("Icons\\.(AutoMirrored\\.)?Outlined\\.$glyph\\b")
        }
        assertThat(screen).doesNotContainMatch("when \\(\\w+\\.section\\)\\s*\\{[^}]*Icons\\.")

        // Instead the root list reads the icon off the catalog, once, and the
        // two other surfaces (sub-screens, search results) opt out.
        assertThat(Regex("leadingIcon = \\{ it\\.section\\.icon \\}").findAll(screen).count()).isEqualTo(1)
        assertThat(Regex("leadingIcon = \\{ null \\}").findAll(screen).count()).isEqualTo(2)

        // Decorative: the shared glyph composable passes a null content
        // description, so TalkBack announces the row's label once.
        val decorative = screen.substringAfter("private fun DecorativeRowIcon(").substringBefore("\n}\n")
        assertThat(decorative).contains("contentDescription = null")
        assertThat(decorative).contains("MaterialTheme.colorScheme.primary")
        // ...and every row-level leading slot goes through it, so no row can
        // sneak in a described (double-announcing) icon.
        assertThat(screen).doesNotContainMatch("leadingContent = \\{ Icon\\(")
    }
}
