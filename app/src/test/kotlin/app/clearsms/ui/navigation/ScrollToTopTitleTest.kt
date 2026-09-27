package app.clearsms.ui.navigation

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Item 2 + issue #46: the unread switch shares the title line and leaves
 * with the expanded title; tapping the title scrolls to the top on every
 * top-level tab.
 *
 * Pure logic for the collapse gate and the scroll target, plus source-level
 * contracts (the repo pattern: no Compose UI harness, no Robolectric
 * gestures) that pin the three tabs to the ONE shared implementation.
 */
class ScrollToTopTitleTest {
    private val srcRoot = File("src/main/kotlin/app/clearsms")

    private fun source(path: String): String {
        val file = File(srcRoot, path)
        assertWithMessage("expected source file $path").that(file.isFile).isTrue()
        return file.readText()
    }

    // ---- Item A: the unread affordance follows the title's collapse state ----

    @Test
    fun `expanded bar shows the affordance, collapsed bar hides it`() {
        assertThat(TitleCollapse.showsExpandedAffordance(0f)).isTrue()
        assertThat(TitleCollapse.showsExpandedAffordance(0.25f)).isTrue()
        assertThat(TitleCollapse.showsExpandedAffordance(1f)).isFalse()
        assertThat(TitleCollapse.showsExpandedAffordance(0.75f)).isFalse()
    }

    @Test
    fun `the boundary is Material's own semantics handover, not an invented threshold`() {
        // TwoRowsTopAppBar hands the title over to the collapsed row at
        // `collapsedFraction < 0.5f`; the affordance leaves at the same point.
        assertThat(TitleCollapse.HANDOVER_FRACTION).isEqualTo(0.5f)
        assertThat(TitleCollapse.showsExpandedAffordance(0.49f)).isTrue()
        assertThat(TitleCollapse.showsExpandedAffordance(0.5f)).isFalse()
        assertThat(TitleCollapse.showsExpandedAffordance(0.51f)).isFalse()
    }

    @Test
    fun `only the expanded row of the two-row bar hosts the affordance`() {
        val typography = Typography(titleLarge = TextStyle(fontSize = 22.sp), headlineMedium = TextStyle(fontSize = 28.sp))
        // The bar composes the same title slot twice; the expanded row is the
        // one given the larger (headline) style.
        assertThat(TitleCollapse.isExpandedRow(typography.headlineMedium, typography)).isTrue()
        assertThat(TitleCollapse.isExpandedRow(typography.titleLarge, typography)).isFalse()
        // A merged style keeps the row's size, so detection survives merging.
        assertThat(TitleCollapse.isExpandedRow(TextStyle(fontSize = 22.sp).merge(typography.headlineMedium), typography)).isTrue()
        // Unspecified sizes never claim the expanded row (fail closed: no
        // invisible-but-tappable switch in the collapsed row).
        assertThat(TitleCollapse.isExpandedRow(TextStyle(), typography)).isFalse()
    }

    @Test
    fun `the title slot gates the affordance on the bar's own collapsed fraction`() {
        val title = source("ui/navigation/ScrollToTopTitle.kt")
        // The bar's state is the ONE input - no scroll offset of our own.
        assertThat(title).contains("TitleCollapse.showsExpandedAffordance(scrollBehavior.state.collapsedFraction)")
        assertThat(title).contains("TitleCollapse.isExpandedRow(LocalTextStyle.current, MaterialTheme.typography)")
        assertThat(title).doesNotContain("firstVisibleItemIndex >")
        assertThat(title).doesNotContain("firstVisibleItemScrollOffset >")
    }

    @Test
    fun `inbox unread switch lives on the title line, no longer in the list`() {
        val inbox = source("ui/inbox/InboxScreen.kt")
        // Passed to the shared title as its expanded-row trailing content...
        assertThat(inbox).contains("expandedTrailing =")
        assertThat(inbox).contains("UnreadSwitch(")
        // ...and gone from the LazyColumn.
        assertThat(inbox).doesNotContain("item(key = \"unread_toggle\")")
        assertThat(inbox).doesNotContain("UnreadToggleRow")
        // Still the same labeled Switch driving the same filter flag.
        assertThat(inbox).contains("Switch(checked = unreadOnly, onCheckedChange = null)")
        assertThat(inbox).contains("onToggleUnread = viewModel::toggleUnread")
    }

    // ---- Item B: tap the title to scroll to top ----

    @Test
    fun `the tap always targets the first item`() {
        assertThat(ScrollToTop.TOP_INDEX).isEqualTo(0)
    }

    @Test
    fun `at the very top nothing needs scrolling, anywhere else does`() {
        assertThat(ScrollToTop.isAtTop(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)).isTrue()
        // Partly scrolled past the first item still counts as "not at top".
        assertThat(ScrollToTop.isAtTop(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 1)).isFalse()
        assertThat(ScrollToTop.isAtTop(firstVisibleItemIndex = 1, firstVisibleItemScrollOffset = 0)).isFalse()
        assertThat(ScrollToTop.isAtTop(firstVisibleItemIndex = 5_000, firstVisibleItemScrollOffset = 37)).isFalse()
    }

    @Test
    fun `scroll-to-top is a genuine animated scroll that also re-expands the bar`() {
        val title = source("ui/navigation/ScrollToTopTitle.kt")
        assertThat(title).contains("listState.animateScrollToItem(ScrollToTop.TOP_INDEX)")
        // A programmatic scroll bypasses the bar's nested-scroll connection,
        // so the bar is animated open explicitly - it must not stay collapsed.
        assertThat(title).contains("animate(initialValue = bar.heightOffset, targetValue = 0f)")
        assertThat(title).contains("bar.contentOffset = 0f")
    }

    @Test
    fun `the title tap is discoverable to a screen reader and only the text is clickable`() {
        val title = source("ui/navigation/ScrollToTopTitle.kt")
        assertThat(title).contains("onClickLabel = stringResource(R.string.action_scroll_to_top)")
        assertThat(title).contains("role = Role.Button")
        assertThat(title).contains(".minimumInteractiveComponentSize()")
        // Exactly one clickable, and it is on the Text - the surrounding Row
        // (which spans up to the action icons) never takes taps.
        assertThat(Regex("\\.clickable\\(").findAll(title).count()).isEqualTo(1)
        assertThat(title.indexOf(".clickable(")).isGreaterThan(title.indexOf("Text("))
        assertThat(title.indexOf(".clickable(")).isLessThan(title.indexOf("maxLines = 1"))
        val strings = File("src/main/res/values/strings_ui.xml").readText()
        assertThat(strings).contains("<string name=\"action_scroll_to_top\">Scroll to top</string>")
    }

    @Test
    fun `all three top-level tabs share the ONE title and hoist their list state to it`() {
        val tabs =
            listOf(
                "ui/inbox/InboxScreen.kt",
                "ui/finance/FinanceScreen.kt",
                "ui/alerts/AlertsScreen.kt",
            )
        tabs.forEach { path ->
            val text = source(path)
            assertWithMessage("$path renders the shared title")
                .that(text)
                .contains("ScrollToTopTitle(")
            assertWithMessage("$path hands the shared title its own bar and list")
                .that(text)
                .containsMatch("ScrollToTopTitle\\(\\s*scrollBehavior = scrollBehavior,\\s*listState = listState[,)]")
            assertWithMessage("$path scrolls the list the title targets")
                .that(text)
                .contains("state = listState")
            assertWithMessage("$path has no title of its own")
                .that(text)
                .doesNotContainMatch("title = \\{ Text\\(stringResource\\(R\\.string\\.(inbox|finance|alerts)_title\\)\\) \\}")
        }
        // The per-tab title strings are gone: one string, one title.
        val strings = File("src/main/res/values/strings_ui.xml").readText()
        listOf("inbox_title", "finance_title", "alerts_title").forEach { name ->
            assertWithMessage("$name should no longer exist").that(strings).doesNotContain("name=\"$name\"")
        }
        val title = source("ui/navigation/ScrollToTopTitle.kt")
        assertThat(title).contains("stringResource(R.string.app_name)")
    }

    @Test
    fun `nothing outside the shared title decides collapse visibility or scrolls to top`() {
        // Drift guard: if a tab grew its own copy of either decision the
        // three tabs could disagree again. (The conversation's reversed list
        // scrolls to ITS index 0 - the newest message - which is a different
        // feature and stays out of scope.)
        val tabDirs = setOf("inbox", "finance", "alerts")
        val offenders =
            File(srcRoot, "ui")
                .walkTopDown()
                .filter { it.extension == "kt" && it.name != "ScrollToTopTitle.kt" }
                .filter { file ->
                    val text = file.readText()
                    text.contains("collapsedFraction") ||
                        (file.parentFile.name in tabDirs && text.contains("animateScrollToItem("))
                }.map { it.relativeTo(srcRoot).path }
                .toList()
        assertThat(offenders).isEmpty()
    }
}
