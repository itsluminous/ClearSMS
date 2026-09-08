package app.clearsms.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Tag-visibility contract across list surfaces. Hiding the category tag
 * under a single-category pill is an inbox-only behaviour (see
 * `InboxFilterState.showsCategoryTags`); search results and the archived
 * list always mix categories, so their rows must keep the tag
 * unconditionally. These are source-level contracts (the repo has no
 * Compose UI test infrastructure) in the same file-reading style as
 * `StringFormatResourcesTest`: they fail if someone reuses the inbox
 * visibility flag - or any other condition - around these badges.
 */
class CategoryTagSurfacesTest {
    private fun source(path: String) = File("src/main/kotlin/app/clearsms/$path").readText()

    @Test
    fun `search results always carry the category tag`() {
        val search = source("ui/search/SearchScreen.kt")
        // The badge leads the row's overline unconditionally; the
        // sender-match label that can sit beside it is conditional, the
        // badge itself must never be - and the inbox-only visibility flag
        // may not leak in.
        val lines = search.lines()
        val badgeIndex = lines.indexOfFirst { "CategoryBadge(category = message.category)" in it }
        assertThat(badgeIndex).isAtLeast(1)
        assertThat(lines[badgeIndex - 1].trim()).doesNotContain("if ")
        assertThat(search).doesNotContain("showsCategoryTags")
    }

    @Test
    fun `archived rows always carry the category tag`() {
        val archived = source("ui/inbox/ArchivedScreen.kt")
        assertThat(archived).contains("CategoryBadge(category = message.category)")
        // Archived has no category pills, so nothing may gate its badge.
        assertThat(archived).doesNotContain("showsCategoryTags")
    }

    @Test
    fun `inbox rows gate the tag on the filter's mixed-category flag`() {
        val inbox = source("ui/inbox/InboxScreen.kt")
        assertThat(inbox).contains("showCategoryTag = state.filter.showsCategoryTags")
    }
}
