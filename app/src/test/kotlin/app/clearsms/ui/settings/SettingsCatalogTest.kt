package app.clearsms.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The settings layout contract: [SettingsItem] declaration order is what the
 * screen renders, so these tests pin the exact section order, the row order
 * within each section, the complete row inventory (nothing lost, nothing
 * duplicated by the reorganisation), and that the search reaches the new
 * sections.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsCatalogTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun title(item: SettingsItem) = context.getString(item.titleRes)

    private fun sectionTitle(section: SettingsSection) = context.getString(section.titleRes)

    @Test
    fun `sections appear in the exact target order`() {
        val sectionsInDisplayOrder =
            SettingsItem.entries
                .mapNotNull { it.section }
                .distinct()
                .map(::sectionTitle)
        assertThat(sectionsInDisplayOrder)
            .containsExactly(
                "Messages",
                "Appearance",
                "Notifications",
                "OTP",
                "Inbox",
                "Finance",
                "Alerts",
                "Startup",
                "Backup & restore",
                "Rules",
                "Signature",
                "About",
                "Donate",
            ).inOrder()
    }

    @Test
    fun `each section's rows are contiguous so headers render exactly once`() {
        val sectionSequence = SettingsItem.entries.map { it.section }
        val distinctRuns =
            sectionSequence.fold(mutableListOf<SettingsSection?>()) { runs, section ->
                if (runs.lastOrNull() != section) runs += section
                runs
            }
        assertThat(distinctRuns).containsNoDuplicates()
    }

    @Test
    fun `rows within each section appear in the exact target order`() {
        val bySection =
            SettingsItem.entries
                .filter { it.section != null }
                .groupBy({ sectionTitle(it.section!!) }, ::title)
        assertThat(bySection["Messages"])
            .containsExactly("Archived messages", "Block & allow list", "Show extracted message details")
            .inOrder()
        assertThat(bySection["Appearance"])
            .containsExactly("Theme", "Dynamic color", "Show logos and contact photos", "Logo background")
            .inOrder()
        assertThat(bySection["Notifications"])
            .containsExactly("SMS delivery reports", "Notification action buttons", "Transaction notifications")
            .inOrder()
        assertThat(bySection["OTP"])
            .containsExactly("Auto copy", "Auto delete OTP", "OTP display size", "Clear older OTPs")
            .inOrder()
        assertThat(bySection["Inbox"])
            .containsExactly(
                "Pill order",
                "Default inbox filter",
                "Swipe right action",
                "Swipe left action",
                "Sort inbox again",
            ).inOrder()
        assertThat(bySection["Finance"])
            .containsExactly("Pill order", "Show balance", "Default Finance filter")
            .inOrder()
        assertThat(bySection["Alerts"]).containsExactly("Pill order")
        assertThat(bySection["Startup"]).containsExactly("Default screen")
        assertThat(bySection["Backup & restore"])
            .containsExactly("Back up now", "Restore", "Back up settings", "Restore settings", "Backup frequency")
            .inOrder()
        assertThat(bySection["Rules"]).containsExactly("Manage rules")
        assertThat(bySection["Signature"]).containsExactly("SMS signature")
        assertThat(bySection["About"]).containsExactly("Version", "Source code").inOrder()
        assertThat(bySection["Donate"]).containsExactly("Paypal", "UPI").inOrder()
    }

    @Test
    fun `row inventory is complete - every pre-reorg row survives and nothing is duplicated`() {
        // The 32 rows that existed before the reorganisation, by title.
        val preReorgRows =
            listOf(
                "Archived messages",
                "Block & allow list",
                "Back up now",
                "Restore",
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
            )
        val allTitles = SettingsItem.entries.map(::title)

        // No row lost, none dropped: 32 survivors + 6 additions = 38 rows.
        assertThat(allTitles.sorted()).isEqualTo((preReorgRows + newRows).sorted())
        // No duplicates: "Pill order" legitimately appears once per pills
        // screen (Inbox / Finance / Alerts); every other (section, title)
        // pair is unique.
        val identity = SettingsItem.entries.map { it.section to title(it) }
        assertThat(identity).containsNoDuplicates()
    }

    @Test
    fun `standalone entries trail all sections in order, with no section`() {
        val standalone = SettingsItem.entries.filter { it.section == null }
        assertThat(standalone.map(::title))
            .containsExactly("Permissions", "Privacy policy", "Open source licenses")
            .inOrder()
        // They are the last three rows — below every section, never inside one.
        assertThat(SettingsItem.entries.takeLast(3)).isEqualTo(standalone)
    }

    private fun search(query: String) = filterSettingsRows(SettingsItem.entries, query, ::title) { "" }

    @Test
    fun `search finds a row in the Messages section`() {
        assertThat(search("block allow").map { it.section }).containsExactly(SettingsSection.MESSAGES)
        assertThat(search("archived")).contains(SettingsItem.ARCHIVED)
    }

    @Test
    fun `search finds rows in the Donate section`() {
        assertThat(search("paypal")).containsExactly(SettingsItem.PAYPAL)
        assertThat(search("upi")).contains(SettingsItem.UPI)
    }

    @Test
    fun `search finds the new About row and the standalone entries`() {
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
}
