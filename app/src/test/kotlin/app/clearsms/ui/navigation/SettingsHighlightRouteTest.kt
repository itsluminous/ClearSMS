package app.clearsms.ui.navigation

import app.clearsms.ui.settings.SettingsItem
import app.clearsms.ui.settings.SettingsSection
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Sending the user to a specific setting. A dialog that says "go to Settings
 * and tap Sort messages again" is a worse instruction than simply taking them
 * there, so the route carries which row to scroll to and flash - the same
 * gesture search uses when it opens a message.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsHighlightRouteTest {
    private fun source(path: String) =
        File(
            listOf("src/main/kotlin/app/clearsms", "app/src/main/kotlin/app/clearsms").first { File(it).isDirectory },
            path,
        ).readText()

    @Test
    fun `the settings route carries an optional highlight`() {
        assertThat(Routes.SETTINGS).isEqualTo("settings?highlight={highlight}")
        assertThat(Routes.settings(SettingsItem.SORT_AGAIN.name)).isEqualTo("settings?highlight=SORT_AGAIN")
    }

    @Test
    fun `plain settings navigation highlights nothing`() {
        // Opening Settings normally must not flash a random row.
        assertThat(Routes.settings()).isEqualTo("settings?highlight=")
    }

    @Test
    fun `the rule wizard points at the sort row, through the one path resolver rather than a literal`() {
        val app = source("ui/navigation/ClearSmsApp.kt")

        // "Sort messages again" lives on the Messages SUB-SCREEN (it moved
        // there from Inbox: it re-reads messages, not the Inbox list), so the
        // link must resolve through Routes.settingsPath (root, then sub-screen
        // with the highlight) - a literal root route would land on Settings
        // with nothing to flash, and a literal section route would have kept
        // pointing at Inbox after the move. Going through the resolver is
        // what re-pointed the wizard's "tap Sort messages again" hint at
        // Messages without the wizard changing.
        assertWithMessage("the wizard's hint must navigate to the highlighted setting via the resolver")
            .that(app)
            .contains("Routes.settingsPath(SettingsItem.SORT_AGAIN).forEach(navController::navigate)")
        assertWithMessage("the wizard must never name the section itself - the catalog decides where the row lives")
            .that(app)
            .doesNotContain("settingsSection(SettingsSection.INBOX, SettingsItem.SORT_AGAIN")
        assertThat(SettingsItem.SORT_AGAIN.section).isEqualTo(SettingsSection.MESSAGES)
        assertThat(Routes.settingsPath(SettingsItem.SORT_AGAIN))
            .containsExactly("settings?highlight=", "settings/section/MESSAGES?highlight=SORT_AGAIN")
            .inOrder()
        assertWithMessage("the wizard must be popped so Back does not return to it")
            .that(app.substringAfter("onOpenSortSetting = {").substringBefore("},"))
            .contains("popBackStack()")
    }

    @Test
    fun `the highlighted row is drawn over its content, not behind it`() {
        val settings = source("ui/settings/SettingsScreen.kt")

        // A settings row is a Material ListItem, which paints an opaque
        // surface: a background tint is invisible behind it. This cost one
        // round of on-device verification, so it is pinned here.
        assertWithMessage("the wash must be drawn over the row")
            .that(settings)
            .contains("drawWithContent")
        assertWithMessage("the row must report its position so the list can scroll to it")
            .that(settings)
            .contains("onGloballyPositioned")
    }

    @Test
    fun `highlight timing is shared with the message highlight`() {
        val conversation = source("ui/conversation/ConversationScreen.kt")
        val settings = source("ui/settings/SettingsScreen.kt")

        for (file in listOf(conversation, settings)) {
            assertWithMessage("both highlights must use the shared timing")
                .that(file)
                .contains("HighlightTiming.")
        }
        assertWithMessage("no screen should keep its own copy of the hold duration")
            .that(conversation)
            .doesNotContain("private const val HIGHLIGHT_HOLD_MS")
    }
}
