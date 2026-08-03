package app.clearsms.ui.inbox

import app.clearsms.domain.model.Category
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InboxFilterStateTest {
    @Test
    fun `unread toggle composes with a selected category`() {
        val state =
            InboxFilterState()
                .selectCategory(Category.IMPORTANT)
                .toggleUnread()
        assertThat(state.category).isEqualTo(Category.IMPORTANT)
        assertThat(state.unreadOnly).isTrue()
    }

    @Test
    fun `changing category keeps the unread toggle`() {
        val state =
            InboxFilterState(category = Category.IMPORTANT, unreadOnly = true)
                .selectCategory(Category.PROMOTIONAL)
        assertThat(state.category).isEqualTo(Category.PROMOTIONAL)
        assertThat(state.unreadOnly).isTrue()
    }

    @Test
    fun `re-selecting the active category clears it but keeps unread`() {
        val state =
            InboxFilterState(category = Category.OTP, unreadOnly = true)
                .selectCategory(Category.OTP)
        assertThat(state.category).isNull()
        assertThat(state.unreadOnly).isTrue()
    }

    @Test
    fun `unread toggles off independently of the category`() {
        val state =
            InboxFilterState(category = Category.PERSONAL, unreadOnly = true)
                .toggleUnread()
        assertThat(state.category).isEqualTo(Category.PERSONAL)
        assertThat(state.unreadOnly).isFalse()
    }

    @Test
    fun `default state is all categories and all read states`() {
        val state = InboxFilterState()
        assertThat(state.category).isNull()
        assertThat(state.unreadOnly).isFalse()
    }

    @Test
    fun `every single-category pill hides the tags, with or without unread`() {
        // Pill customisation only reorders Category entries (orderedPills
        // drops unknowns and appends omissions), so Category.entries IS the
        // complete set of category pills in any user-configured order.
        Category.entries.forEach { pill ->
            assertThat(InboxFilterState(category = pill).showsCategoryTags).isFalse()
            assertThat(InboxFilterState(category = pill, unreadOnly = true).showsCategoryTags).isFalse()
        }
    }

    @Test
    fun `the all view shows the tags`() {
        assertThat(InboxFilterState().showsCategoryTags).isTrue()
    }

    @Test
    fun `unread without a category mixes categories so tags stay visible`() {
        assertThat(InboxFilterState(unreadOnly = true).showsCategoryTags).isTrue()
    }

    @Test
    fun `clearing the active pill brings the tags back`() {
        val state = InboxFilterState(category = Category.IMPORTANT).selectCategory(Category.IMPORTANT)
        assertThat(state.showsCategoryTags).isTrue()
    }
}
