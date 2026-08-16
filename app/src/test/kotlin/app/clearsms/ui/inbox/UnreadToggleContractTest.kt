package app.clearsms.ui.inbox

import app.clearsms.domain.model.Category
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Contract for the "Unread only" control after its move out of the pill row.
 *
 * Users read the pill row as one vocabulary: chips select exclusively. The
 * Unread chip broke that (it composed with any category), so it now lives as
 * a right-aligned labeled Switch ABOVE the pills. These are source-level
 * contracts (the repo has no Compose UI test infrastructure, same style as
 * `CategoryTagSurfacesTest`) plus state-level assertions that the move did
 * not change filter semantics.
 */
class UnreadToggleContractTest {
    private fun source(path: String) = File("src/main/kotlin/app/clearsms/$path").readText()

    @Test
    fun `pill row contains exactly the category chips - no unread chip`() {
        val inbox = source("ui/inbox/InboxScreen.kt")
        // The old chip was the item keyed "unread" whose selection bound unreadOnly.
        assertThat(inbox).doesNotContain("item(key = \"unread\")")
        assertThat(inbox).doesNotContain("selected = filter.unreadOnly")
        // The row's only content is the reorderable category pills.
        assertThat(inbox).contains("items(orderedPills(pillOrder, Category.entries.toList())")
    }

    @Test
    fun `unread toggle sits above the pill row`() {
        val inbox = source("ui/inbox/InboxScreen.kt")
        val toggle = inbox.indexOf("item(key = \"unread_toggle\")")
        val pills = inbox.indexOf("item(key = \"filters\")")
        assertThat(toggle).isGreaterThan(-1)
        assertThat(pills).isGreaterThan(-1)
        assertThat(toggle).isLessThan(pills)
        // Right-aligned, and a Switch (view mode), not another chip.
        assertThat(inbox).contains("horizontalArrangement = Arrangement.End")
        assertThat(inbox).contains("Switch(checked = unreadOnly, onCheckedChange = null)")
    }

    @Test
    fun `toggle drives the same InboxFilterState unread flag as the old chip`() {
        val inbox = source("ui/inbox/InboxScreen.kt")
        assertThat(inbox).contains("onToggleUnread = viewModel::toggleUnread")
        // And that flag still composes with a selected category, both ways.
        val state = InboxFilterState(category = Category.IMPORTANT).toggleUnread()
        assertThat(state.unreadOnly).isTrue()
        assertThat(state.category).isEqualTo(Category.IMPORTANT)
        assertThat(state.toggleUnread().unreadOnly).isFalse()
        assertThat(state.selectCategory(Category.OTP).unreadOnly).isTrue()
    }

    @Test
    fun `unread count surfaces on the toggle label`() {
        val inbox = source("ui/inbox/InboxScreen.kt")
        assertThat(inbox).contains("stringResource(R.string.inbox_unread_toggle_count, totalUnread)")
        val strings = File("src/main/res/values/strings_ui.xml").readText()
        assertThat(strings).contains("<string name=\"inbox_unread_toggle_count\">Unread · %1\$d</string>")
    }

    @Test
    fun `pill order customization can never offer Unread`() {
        // Unread is a filter flag, not a Category, so neither the Settings
        // reorder sheet (built from Category.entries) nor a stored order can
        // ever produce an Unread pill.
        assertThat(Category.entries.map { it.name }).doesNotContain("UNREAD")
        val settings = source("ui/settings/SettingsScreen.kt")
        assertThat(settings).contains("order = orderedPills(order, Category.entries.toList())")
    }

    @Test
    fun `archived screen keeps no pill row and no unread control`() {
        val archived = source("ui/inbox/ArchivedScreen.kt")
        assertThat(archived).doesNotContain("FilterChipRow")
        assertThat(archived).doesNotContain("filter_unread")
        assertThat(archived).doesNotContain("UnreadToggleRow")
    }
}
