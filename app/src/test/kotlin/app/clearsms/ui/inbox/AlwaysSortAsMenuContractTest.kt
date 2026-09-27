package app.clearsms.ui.inbox

import app.clearsms.domain.model.Category
import app.clearsms.domain.rules.SenderRule
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Contract for "Always sort as…" in the inbox selection overflow. Source-level
 * contracts (the repo deliberately has no Compose UI test infrastructure, same
 * style as `MessageDetailsMenuContractTest`) plus the layout-object gating:
 * the entry exists in the overflow, appears ONLY for a single selected
 * thread, routes to the ONE shared [app.clearsms.ui.rules.SenderRuleDialog]
 * rather than a copy of it, clears the selection once the save has re-sorted
 * the sender's rows, and offers the category list itself - so a category
 * added to [Category] (SPAM) reaches the chips with no menu change.
 */
class AlwaysSortAsMenuContractTest {
    private fun source(path: String) = File("src/main/kotlin/app/clearsms/$path").readText()

    private fun mainSources(): Sequence<File> = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }

    @Test
    fun `entry sits in the overflow and is gated to exactly one selected thread`() {
        val single = SelectionBarLayout.overflowActions(allSelectedPinned = false, singleThread = true)
        assertThat(single).contains(SelectionAction.ALWAYS_SORT_AS)
        // Hidden, not disabled, for multi-select: inbox threads are one per
        // sender core, so several threads are always several senders and
        // "always sort THIS sender as" has no single answer.
        val multi = SelectionBarLayout.overflowActions(allSelectedPinned = false, singleThread = false)
        assertThat(multi).doesNotContain(SelectionAction.ALWAYS_SORT_AS)
        // The existing entries are untouched by the addition, in both modes.
        assertThat(multi).containsExactly(SelectionAction.PIN, SelectionAction.SELECT_ALL).inOrder()
        assertThat(single)
            .containsExactly(
                SelectionAction.PIN,
                SelectionAction.SELECT_ALL,
                SelectionAction.BLOCK,
                SelectionAction.ALWAYS_SORT_AS,
            ).inOrder()
        // Never inline: it must not displace the read/archive/delete trio.
        assertThat(SelectionBarLayout.inlineActions).doesNotContain(SelectionAction.ALWAYS_SORT_AS)
    }

    @Test
    fun `the inbox renders the overflow FROM the layout object and labels the entry like the dialog`() {
        val inbox = source("ui/inbox/InboxScreen.kt")
        // The menu is built from the gated list - the screen cannot re-decide
        // (or forget) the single-thread rule on its own.
        assertThat(inbox).contains("SelectionBarLayout.overflowActions(")
        assertThat(inbox).contains("singleThread = singleItem != null,")
        assertThat(inbox).contains("SelectionAction.ALWAYS_SORT_AS ->")
        assertThat(inbox).contains("stringResource(R.string.action_always_sort_as)")
        assertThat(inbox).doesNotContain("if (singleItem != null) {")
        val strings = File("src/main/res/values/strings_ui.xml").readText()
        assertThat(strings).contains("<string name=\"action_always_sort_as\">Always sort as…</string>")
        // Same words as the dialog title it opens.
        assertThat(strings).contains("<string name=\"sender_rule_title\">Always sort as…</string>")
    }

    @Test
    fun `the entry opens the one shared SenderRuleDialog - no second copy of the flow`() {
        val inbox = source("ui/inbox/InboxScreen.kt")
        assertThat(inbox).contains("onAlwaysSortAs(it.message.sender, it.message.body)")
        assertThat(inbox).contains("senderRuleTarget = sender to body")
        assertThat(inbox).contains("SenderRuleDialog(")
        // The inbox never touches the rule machinery itself: no category
        // chips, no rule definition, no save of its own.
        assertThat(inbox).doesNotContain("SenderRule.CATEGORIES")
        assertThat(inbox).doesNotContain("SenderRule.definition(")
        assertThat(inbox).doesNotContain("SenderRuleViewModel")
        // Exactly one dialog definition and one save path in the whole app.
        val dialogDefinitions = mainSources().count { "fun SenderRuleDialog(" in it.readText() }
        assertThat(dialogDefinitions).isEqualTo(1)
        val savers = mainSources().filter { "SenderRule.definition(" in it.readText() }.map { it.name }.toList()
        assertThat(savers).containsExactly("SenderRuleViewModel.kt")
        // Both entry points (inbox and conversation) open that same dialog.
        assertThat(source("ui/conversation/ConversationScreen.kt")).contains("SenderRuleDialog(")
    }

    @Test
    fun `saving clears the selection after the re-sort - cancelling keeps it`() {
        val inbox = source("ui/inbox/InboxScreen.kt")
        val dialog = inbox.substring(inbox.indexOf("senderRuleTarget?.let"))
        val onSaved = dialog.substring(dialog.indexOf("onSaved = {"), dialog.indexOf("onDetailedRule = {"))
        // The save is what moves rows (recategorizeSenderCore), so the
        // selection is dropped there, before the confirmation snackbar.
        assertThat(onSaved).contains("viewModel.exitSelection()")
        assertThat(onSaved.indexOf("viewModel.exitSelection()")).isLessThan(onSaved.indexOf("showSnackbar"))
        // Leaving for the detailed wizard also leaves selection mode.
        val onDetailed = dialog.substring(dialog.indexOf("onDetailedRule = {"), dialog.indexOf("onCreateRule(sender, body)"))
        assertThat(onDetailed).contains("viewModel.exitSelection()")
        // Cancel: the dialog closes and nothing else happens - the rows the
        // user picked are still there, still selected.
        assertThat(dialog).contains("onDismiss = { senderRuleTarget = null },")
        // And choosing the menu entry no longer exits selection up front.
        val onChoose = inbox.substring(inbox.indexOf("onAlwaysSortAs = {"), inbox.indexOf("senderRuleTarget = sender to body"))
        assertThat(onChoose).doesNotContain("exitSelection")
    }

    @Test
    fun `category options come from the category list, so SPAM appears without a menu change`() {
        val dialog = source("ui/rules/SenderRuleDialog.kt")
        assertThat(dialog).contains("SenderRule.CATEGORIES.forEach { option ->")
        assertThat(dialog).doesNotContain("listOf(\"important\"")
        assertThat(SenderRule.CATEGORIES).containsExactlyElementsIn(Category.entries.map { it.name.lowercase() }).inOrder()
        assertThat(SenderRule.CATEGORIES).contains("spam")
        // The menu itself knows no category at all.
        assertThat(source("ui/inbox/InboxScreen.kt")).doesNotContain("\"spam\"")
        assertThat(source("ui/inbox/SelectionBarLayout.kt")).doesNotContain("Category.")
    }
}
