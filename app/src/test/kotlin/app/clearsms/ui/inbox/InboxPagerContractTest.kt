package app.clearsms.ui.inbox

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Source-level contract for what may rebuild a paged list.
 *
 * `flatMapLatest { Pager(...) }` destroys the running pager and starts a
 * fresh `PagingData` stream - from the top - on EVERY upstream emission. So
 * the upstream must be exactly the query key, `distinctUntilChanged`, and
 * nothing presentational (unread counts, contact names, banners, a raw
 * DataStore flow that re-emits on unrelated writes) may ever be combined
 * into it again. These checks pin that shape (the repo has no Compose UI
 * test harness; same style as `UnreadToggleContractTest`).
 */
class InboxPagerContractTest {
    private fun source(path: String) = File("src/main/kotlin/app/clearsms/$path").readText()

    /** The paged-flow declaration ([name]) up to its `cachedIn`. */
    private fun pagerDeclaration(
        file: String,
        name: String = "pagedItems",
    ): String {
        val start = file.indexOf("val $name:")
        assertThat(start).isGreaterThan(-1)
        val end = file.indexOf(".cachedIn(viewModelScope)", start)
        assertThat(end).isGreaterThan(start)
        return file.substring(start, end)
    }

    @Test
    fun `inbox pager is rebuilt from the query key only`() {
        val vm = source("ui/inbox/InboxViewModel.kt")
        val pager = pagerDeclaration(vm)

        // The one and only upstream is the de-duplicated key flow.
        assertThat(vm).contains("val pagerKeys: Flow<InboxPagerKey> = inboxPagerKeys(effectiveFilter, settings.messageSortOrder)")
        assertThat(pager).contains("pagerKeys\n                .flatMapLatest { key ->")
        // Nothing else is combined in front of the flatMapLatest.
        assertThat(pager).doesNotContain("combine(")
        assertThat(pager).doesNotContain("observeUnreadCounts")
        assertThat(pager).doesNotContain("contactsTick")
        assertThat(pager).doesNotContain("uiState")
        // The query is read from the key, never from a captured flow value.
        assertThat(pager).contains("pagedInbox(key.category, key.unreadOnly, key.sortOrder)")
    }

    @Test
    fun `inbox key flow de-duplicates the DataStore-backed sort order`() {
        val key = source("ui/inbox/InboxPagerKey.kt")
        assertThat(key).contains("combine(filters, sortOrders, InboxPagerKey::of).distinctUntilChanged()")
    }

    @Test
    fun `contacts becoming available refreshes rows in place instead of rebuilding the pager`() {
        val vm = source("ui/inbox/InboxViewModel.kt")
        assertThat(vm).doesNotContain("contactsTick")
        val granted = vm.substringAfter("fun onContactsPermissionGranted()").substringBefore("\n        }")
        assertThat(granted).contains("activePagingSource?.invalidate()")
    }

    @Test
    fun `row mapping stays above cachedIn so the cached page event survives`() {
        // PagingData.map after cachedIn drops the cached Insert event that
        // LazyPagingItems seeds from; the list would then always come back
        // empty and lose its scroll position on every return.
        for (
        (path, name) in
        listOf(
            "ui/inbox/InboxViewModel.kt" to "pagedItems",
            "ui/conversation/ConversationViewModel.kt" to "pagedItems",
            "ui/search/SearchViewModel.kt" to "pagedResults",
        )
        ) {
            val pager = pagerDeclaration(source(path), name)
            assertThat(pager).contains(".map { data -> data.map {")
        }
    }

    @Test
    fun `conversation pager de-duplicates the DataStore-backed sort order`() {
        val pager = pagerDeclaration(source("ui/conversation/ConversationViewModel.kt"))
        assertThat(pager).contains(
            "settings.messageSortOrder\n                .distinctUntilChanged()\n                .flatMapLatest { sortOrder ->",
        )
    }

    @Test
    fun `search pager is rebuilt from a de-duplicated request key`() {
        val pager = pagerDeclaration(source("ui/search/SearchViewModel.kt"), "pagedResults")
        assertThat(
            pager,
        ).contains(
            "{ text, cat, date -> Request(text, cat, cutoffMs(date)) }\n                .distinctUntilChanged()\n                .flatMapLatest",
        )
    }
}
