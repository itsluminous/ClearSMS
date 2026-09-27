package app.clearsms.ui.navigation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.clearsms.ui.settings.SettingsItem
import app.clearsms.ui.settings.SettingsSection
import app.clearsms.ui.settings.filterSettingsRows
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The hard requirement of the Settings split: search still finds rows that
 * now live INSIDE sub-screens, shows which section they belong to, and a
 * tap takes the user to that sub-screen with the row flashed - the same
 * highlight contract [SettingsHighlightRouteTest] pins for deep links.
 *
 * The search is the root screen's `searchSettingsRows` (title, summary and
 * section name); the navigation target is [Routes.settingsPath] for a deep
 * link and its last element - [Routes.settingsSection] with the row's
 * highlight - for a hit tapped on the root screen. Both halves are pure
 * functions of the catalog, so the whole contract is testable without a
 * device: break the sub-screen targeting (say, point a nested hit at the
 * root route again) and these fail.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsNestedSearchTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun title(item: SettingsItem) = context.getString(item.titleRes)

    private fun sectionTitle(section: SettingsSection) = context.getString(section.titleRes)

    /** The root screen's search: title, plus section name and summary as the haystack. */
    private fun search(query: String) = filterSettingsRows(SettingsItem.entries, query, ::title) { sectionTitle(it.section) }

    /** The `{section}` / `{highlight}` a NavHost parses out of a concrete sub-screen route. */
    private fun parseSectionRoute(route: String): Pair<String, String> {
        val pattern =
            Regex(
                "^" +
                    Regex
                        .escape(Routes.SETTINGS_SECTION)
                        .replace("{section}", "\\E([^/?]+)\\Q")
                        .replace("{highlight}", "\\E(.*)\\Q") +
                    "$",
            )
        val match = pattern.matchEntire(route) ?: error("$route does not match ${Routes.SETTINGS_SECTION}")
        return match.groupValues[1] to match.groupValues[2]
    }

    private fun source(path: String) =
        File(
            listOf("src/main/kotlin/app/clearsms", "app/src/main/kotlin/app/clearsms").first { File(it).isDirectory },
            path,
        ).readText()

    @Test
    fun `a term matching a nested row finds it, with the section it belongs to`() {
        // "Sort inbox again" moved from the long root list onto the Inbox
        // sub-screen. The search must still surface it, and say where it is.
        val hits = search("sort inbox")
        assertThat(hits).containsExactly(SettingsItem.SORT_AGAIN)
        assertThat(hits.single().section).isEqualTo(SettingsSection.INBOX)
        assertThat(sectionTitle(hits.single().section)).isEqualTo("Inbox")
        assertThat(hits.single().nested).isTrue()
    }

    @Test
    fun `the three rows that moved inside About are found and attributed to About`() {
        for (
        (query, item) in
        listOf(
            "permissions" to SettingsItem.PERMISSIONS,
            "privacy policy" to SettingsItem.PRIVACY_POLICY,
            "licenses" to SettingsItem.LICENSES,
        )
        ) {
            val hits = search(query)
            assertWithMessage(query).that(hits).contains(item)
            assertThat(item.section).isEqualTo(SettingsSection.ABOUT)
        }
    }

    @Test
    fun `searching a section's name lists that section's rows`() {
        // The section name is part of the haystack, so a user who only knows
        // "it's somewhere under About" gets the whole About screen.
        assertThat(search("about")).containsExactlyElementsIn(SettingsSection.ABOUT.items)
        assertThat(search("otp")).containsAtLeastElementsIn(SettingsSection.OTP.items)
    }

    @Test
    fun `a nested hit navigates to its SUB-SCREEN with the row highlighted`() {
        val target = Routes.settingsPath(SettingsItem.SORT_AGAIN).last()
        val (section, highlight) = parseSectionRoute(target)
        // The sub-screen, not the root: the root has no such row to flash.
        assertThat(section).isEqualTo(SettingsSection.INBOX.name)
        assertThat(highlight).isEqualTo(SettingsItem.SORT_AGAIN.name)
        assertThat(target).isEqualTo(Routes.settingsSection(SettingsSection.INBOX, SettingsItem.SORT_AGAIN.name))
        assertThat(target).isNotEqualTo(Routes.settings(SettingsItem.SORT_AGAIN.name))
    }

    @Test
    fun `every nested row's target is its own section's sub-screen, flashing that row`() {
        for (item in SettingsItem.entries.filter { it.nested }) {
            val path = Routes.settingsPath(item)
            // Root first, so Back from the sub-screen lands on Settings.
            assertWithMessage("$item must push Settings beneath its sub-screen")
                .that(path.first())
                .isEqualTo(Routes.settings())
            val (section, highlight) = parseSectionRoute(path.last())
            assertWithMessage("$item must open the sub-screen of its own section")
                .that(section)
                .isEqualTo(item.section.name)
            assertWithMessage("$item must arrive flashed on that sub-screen")
                .that(highlight)
                .isEqualTo(item.name)
            // ...and the row really is on that screen, so there is something to flash.
            assertThat(item.section.items).contains(item)
        }
    }

    @Test
    fun `a direct (top-level) row's target is the root screen with the row highlighted`() {
        for (item in SettingsItem.entries.filterNot { it.nested }) {
            assertThat(Routes.settingsPath(item)).containsExactly(Routes.settings(item.name))
        }
        assertThat(Routes.settingsPath(SettingsItem.SIGNATURE)).containsExactly("settings?highlight=SIGNATURE")
    }

    @Test
    fun `a concrete sub-screen route parses back into the section and highlight the NavHost reads`() {
        for (section in SettingsSection.entries.filter { it.subScreen }) {
            assertThat(parseSectionRoute(Routes.settingsSection(section)))
                .isEqualTo(section.name to "")
            val row = section.items.first()
            assertThat(parseSectionRoute(Routes.settingsSection(section, row.name)))
                .isEqualTo(section.name to row.name)
        }
        // The sub-screen route can never be mistaken for one of the fixed
        // settings/… routes, and vice versa.
        for (fixed in listOf(Routes.PRIVACY_POLICY, Routes.LICENSES, Routes.PERMISSIONS_INFO)) {
            assertThat(fixed).doesNotContain("settings/section/")
        }
    }

    @Test
    fun `the root screen sends a tapped nested hit to its sub-screen, and the shell routes it with the highlight`() {
        val settings = source("ui/settings/SettingsScreen.kt")
        val shell = source("ui/navigation/ClearSmsApp.kt")

        // A nested search result is a navigation row that asks for its
        // section's sub-screen WITH the row (not the section alone).
        val searchBranch = settings.substringAfter("val results =").substringBefore("SettingsRowList(")
        assertWithMessage("a nested hit must open its section with itself as the highlight")
            .that(searchBranch)
            .contains("onOpenSection(item.section, item)")
        // The shell turns that into the sub-screen route carrying the highlight...
        assertWithMessage("the shell must route onOpenSection to the sub-screen with the highlight")
            .that(shell)
            .contains("navController.navigate(Routes.settingsSection(section, item?.name))")
        // ...and the sub-screen composable receives that highlight to flash.
        val sectionComposable = shell.substringAfter("route = Routes.SETTINGS_SECTION").substringBefore("composable(Routes.PRIVACY_POLICY)")
        assertWithMessage("the sub-screen must be handed the highlight argument")
            .that(sectionComposable)
            .contains("highlight = entry.arguments?.getString(\"highlight\").toSettingsItem()")
        assertThat(sectionComposable).contains("SettingsSectionScreen(")
    }

    @Test
    fun `the sub-screen flashes exactly the highlighted row - the same wash and timing as before`() {
        val settings = source("ui/settings/SettingsScreen.kt")
        val subScreen = settings.substringAfter("fun SettingsSectionScreen(").substringBefore("fun searchSettingsRows(")
        assertWithMessage("the sub-screen must show its own section's rows only")
            .that(subScreen)
            .contains("rows.filter { it.section == section }")
        assertWithMessage("the sub-screen must target the highlighted row")
            .that(subScreen)
            .contains("row.item == highlight")
        // The shared list still scrolls to and flashes on the shared clock.
        assertThat(settings).contains("HighlightTiming.HOLD_MS")
        assertThat(settings).contains("scrollState.animateScrollTo(")
    }
}
