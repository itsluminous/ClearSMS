package app.clearsms.ui.navigation

import app.clearsms.ui.settings.SettingsItem
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Sending the user to a specific setting. A dialog that says "go to Settings
 * and tap Sort inbox again" is a worse instruction than simply taking them
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
    fun `the rule wizard points at the sort row, by catalog name rather than a literal`() {
        val app = source("ui/navigation/ClearSmsApp.kt")

        assertWithMessage("the wizard's hint must navigate to the highlighted setting")
            .that(app)
            .contains("Routes.settings(SettingsItem.SORT_AGAIN.name)")
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
