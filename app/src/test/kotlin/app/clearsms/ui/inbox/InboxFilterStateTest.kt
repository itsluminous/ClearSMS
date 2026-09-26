package app.clearsms.ui.inbox

import app.clearsms.domain.model.Category
import app.clearsms.domain.model.InboxPill
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InboxFilterStateTest {
    @Test
    fun `unread toggle composes with a selected category`() {
        val state =
            InboxFilterState()
                .selectPill(InboxPill.IMPORTANT)
                .toggleUnread()
        assertThat(state.category).isEqualTo(Category.IMPORTANT)
        assertThat(state.unreadOnly).isTrue()
    }

    @Test
    fun `changing category keeps the unread toggle`() {
        val state =
            InboxFilterState(pill = InboxPill.IMPORTANT, unreadOnly = true)
                .selectPill(InboxPill.PROMOTIONAL)
        assertThat(state.category).isEqualTo(Category.PROMOTIONAL)
        assertThat(state.unreadOnly).isTrue()
    }

    @Test
    fun `re-selecting the active category clears it but keeps unread`() {
        val state =
            InboxFilterState(pill = InboxPill.OTP, unreadOnly = true)
                .selectPill(InboxPill.OTP)
        assertThat(state.category).isNull()
        assertThat(state.pill).isNull()
        assertThat(state.unreadOnly).isTrue()
    }

    @Test
    fun `unread toggles off independently of the category`() {
        val state =
            InboxFilterState(pill = InboxPill.PERSONAL, unreadOnly = true)
                .toggleUnread()
        assertThat(state.category).isEqualTo(Category.PERSONAL)
        assertThat(state.unreadOnly).isFalse()
    }

    @Test
    fun `default state is all categories and all read states`() {
        val state = InboxFilterState()
        assertThat(state.category).isNull()
        assertThat(state.scamOnly).isFalse()
        assertThat(state.unreadOnly).isFalse()
    }

    @Test
    fun `every single-category pill hides the tags, with or without unread`() {
        InboxPill.entries.filter { it.category != null }.forEach { pill ->
            assertThat(InboxFilterState(pill = pill).showsCategoryTags).isFalse()
            assertThat(InboxFilterState(pill = pill, unreadOnly = true).showsCategoryTags).isFalse()
        }
    }

    @Test
    fun `the scam pill spans categories so its rows keep their tags`() {
        val state = InboxFilterState(pill = InboxPill.SCAM)
        assertThat(state.scamOnly).isTrue()
        assertThat(state.category).isNull()
        assertThat(state.showsCategoryTags).isTrue()
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
        val state = InboxFilterState(pill = InboxPill.IMPORTANT).selectPill(InboxPill.IMPORTANT)
        assertThat(state.showsCategoryTags).isTrue()
    }
}
