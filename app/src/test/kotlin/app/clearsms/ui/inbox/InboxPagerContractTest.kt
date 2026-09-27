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
    fun `paged lists keep the full index space with placeholders`() {
        // A Room write while the screen is gone makes Paging refresh AROUND
        // the last accessed row. Without placeholders that window is
        // presented from index 0 and the LazyListState restored to row N
        // (saved state holds an index, not a key) is clamped to the window's
        // end, or to 0 when the refresh had no anchor left.
        for (
        (path, name) in
        listOf(
            "ui/inbox/InboxViewModel.kt" to "pagedItems",
            "ui/search/SearchViewModel.kt" to "pagedResults",
        )
        ) {
            assertThat(pagerDeclaration(source(path), name)).contains("enablePlaceholders = true")
        }
        for (screen in listOf("ui/inbox/InboxScreen.kt", "ui/search/SearchScreen.kt")) {
            val src = source(screen)
            // Null rows keep a row's height, never collapse to zero.
            assertThat(src).doesNotContain("[index] ?: return@items")
            assertThat(src).contains("PagedRowPlaceholder()")
        }
    }

    @Test
    fun `paged lists are not measured before their first page exists`() {
        // The returned-to generation has loaded nothing until collected; a
        // first measure with only the chrome present would clamp the
        // restored index to it.
        val helper = source("ui/components/PagedListRestore.kt")
        assertThat(helper).contains("scrolled && items.itemCount == 0 && items.loadState.refresh is LoadState.Loading")
        assertThat(source("ui/inbox/InboxScreen.kt"))
            .contains("} else if (!awaitingFirstPage) {\n                LazyColumn(state = listState")
        assertThat(source("ui/search/SearchScreen.kt"))
            .contains("if (listState.awaitingFirstPage(results)) return@Scaffold\n        LazyColumn(\n            state = listState")
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
