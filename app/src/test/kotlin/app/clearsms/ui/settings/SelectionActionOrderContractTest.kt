package app.clearsms.ui.settings

import app.clearsms.ui.conversation.ConversationSelectionBarLayout
import app.clearsms.ui.conversation.MessageSelectionAction
import app.clearsms.ui.inbox.SelectionAction
import app.clearsms.ui.inbox.SelectionBarLayout
import app.clearsms.ui.navigation.PillConfig
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import app.clearsms.testing.DefaultStrings

/**
 * Contracts for the user-ordered conversation selection-bar actions (issue
 * #61), in the repo's source-contract style:
 *
 * 1. ONE ordering mechanism: the actions resolve through the same
 *    [PillConfig] and are edited by the same [PillOrderDialog] as the
 *    Inbox / Finance / Alerts pills - no second implementation.
 * 2. ORDER ONLY: there is no hidden set for actions; every action is always
 *    reachable (inline or in the overflow).
 * 3. The settings row lives in Messages, right after the other
 *    conversation-view rows.
 * 4. The setting is conversation-only: the inbox selection bar keeps its
 *    fixed layout and does not read the preference.
 */
class SelectionActionOrderContractTest {
    private fun source(path: String) = File("src/main/kotlin/app/clearsms/$path").readText()

    private val layout = source("ui/conversation/ConversationSelectionBarLayout.kt")
    private val settings = source("ui/settings/SettingsScreen.kt")
    private val viewModel = source("ui/settings/SettingsViewModel.kt")
    private val repoImpl = source("data/prefs/SettingsRepositoryImpl.kt")

    @Test
    fun `the actions resolve through the shared PillConfig - same mechanism as the pills`() {
        assertThat(layout).contains("import app.clearsms.ui.navigation.PillConfig")
        assertThat(layout).contains("PillConfig(MessageSelectionAction.entries.toList(), order).ordered")
        assertThat(viewModel).contains("PillConfig(MessageSelectionAction.entries.toList(), order)")
        // Behavioural half: the layout's resolution IS PillConfig's.
        val order = listOf(MessageSelectionAction.SHARE, MessageSelectionAction.COPY)
        assertThat(ConversationSelectionBarLayout.ordered(order))
            .isEqualTo(PillConfig(MessageSelectionAction.entries.toList(), order).ordered)
        // The repository decodes the names with the one lenient reader the
        // pill orders use - not a private copy.
        assertThat(repoImpl).contains("it[KEY_MESSAGE_SELECTION_ACTION_ORDER].toEnumOrder()")
        assertThat(repoImpl).contains("it[KEY_MESSAGE_SELECTION_ACTION_ORDER] = value.toStoredOrder()")
        assertThat(repoImpl).contains("stringPreferencesKey(\"message_selection_action_order\")")
    }

    @Test
    fun `the actions are edited by the shared PillOrderDialog, with its own hint`() {
        val block =
            settings
                .substringAfter(
                    "SettingsDialog.SELECTION_ACTION_ORDER -> {",
                ).substringBefore("SettingsDialog.LOGO_BACKGROUND ->")
        assertThat(block).contains("PillOrderDialog(")
        assertThat(block).contains("order = actions.ordered,")
        assertThat(block).contains("onOrderChange = viewModel::setSelectionActionOrder,")
        assertThat(block).contains("viewModel.resetSelectionActionOrder()")
        assertThat(block).contains("hint = stringResource(R.string.selection_action_order_drag_hint),")
        // Reset returns to the layout's default, not to some second list.
        assertThat(
            viewModel,
        ).contains("fun resetSelectionActionOrder() = setSelectionActionOrder(ConversationSelectionBarLayout.defaultOrder)")
        // The dialog's hint is a parameter with the pill wording as default,
        // so the three pill dialogs are untouched.
        val dialog = source("ui/settings/PillOrderDialog.kt")
        assertThat(dialog).contains("hint: String = stringResource(R.string.pill_order_drag_hint),")
        assertThat(dialog).contains("text = hint,")
        val strings = DefaultStrings.ui
        assertThat(strings).contains("<string name=\"selection_action_order_drag_hint\">")
        assertThat(strings).contains("<string name=\"settings_selection_action_order\">Message action order</string>")
    }

    @Test
    fun `order only - no hidden set for actions anywhere`() {
        val main = File("src/main/kotlin").walk().filter { it.extension == "kt" }.toList()
        val offenders =
            main.filter { file ->
                val text = file.readText()
                listOf("hiddenSelectionActions", "HiddenSelectionActions", "message_selection_hidden", "hidden_selection_actions")
                    .any { it in text }
            }
        assertThat(offenders).isEmpty()
        // The ViewModel builds the config with NO hidden argument.
        assertThat(viewModel).doesNotContain("PillConfig(MessageSelectionAction.entries.toList(), order, ")
        assertThat(layout).doesNotContain("hidden")
        // And the resolved bar never omits an applicable action (the layout test
        // proves this exhaustively; this is the one-line reminder).
        val bar = ConversationSelectionBarLayout.resolveDefault(singleMessage = true, hasOtp = true)
        assertThat(bar.inline + bar.overflow).containsExactlyElementsIn(MessageSelectionAction.entries)
    }

    @Test
    fun `the settings row is in Messages right after Sort messages by`() {
        assertThat(SettingsItem.SELECTION_ACTION_ORDER.section).isEqualTo(SettingsSection.MESSAGES)
        val messagesRows = SettingsItem.entries.filter { it.section == SettingsSection.MESSAGES }
        assertThat(messagesRows.indexOf(SettingsItem.SELECTION_ACTION_ORDER))
            .isEqualTo(messagesRows.indexOf(SettingsItem.MESSAGE_SORT_ORDER) + 1)
        assertThat(messagesRows.last()).isEqualTo(SettingsItem.SELECTION_ACTION_ORDER)
        // Searchability (needs resources) is pinned in SettingsCatalogTest.
        // The row opens the dialog and summarises the inline trio.
        assertThat(settings).contains("SettingsItem.SELECTION_ACTION_ORDER -> {")
        assertThat(settings).contains("openDialog(SettingsDialog.SELECTION_ACTION_ORDER)")
        assertThat(settings).contains("selectionActionOrderSummary(actions.ordered)")
        assertThat(settings).contains("ordered.take(ConversationSelectionBarLayout.INLINE_SLOTS)")
    }

    @Test
    fun `labels are the bar's own strings - Settings and the bar cannot disagree`() {
        val screen = source("ui/conversation/ConversationScreen.kt")
        val labels =
            mapOf(
                MessageSelectionAction.COPY to "action_copy_message",
                MessageSelectionAction.DELETE to "ui_action_delete",
                MessageSelectionAction.MORE_DETAILS to "action_message_details",
                MessageSelectionAction.FORWARD to "action_forward_message",
                MessageSelectionAction.SHARE to "action_share_message",
                MessageSelectionAction.SELECT_ALL to "action_select_all",
                MessageSelectionAction.COPY_OTP to "action_copy_otp",
                MessageSelectionAction.ADD_RULE to "action_add_rule",
            )
        assertThat(labels.keys).containsExactlyElementsIn(MessageSelectionAction.entries)
        for ((action, res) in labels) {
            assertThat(settings).contains("MessageSelectionAction.${action.name} -> R.string.$res")
            assertThat(screen).contains("stringResource(R.string.$res)")
        }
    }

    @Test
    fun `conversation-only - the inbox selection bar keeps its fixed layout and ignores the preference`() {
        assertThat(SelectionBarLayout.inlineActions)
            .containsExactly(SelectionAction.TOGGLE_READ, SelectionAction.ARCHIVE, SelectionAction.DELETE)
            .inOrder()
        val inbox = source("ui/inbox/SelectionBarLayout.kt")
        assertThat(inbox).doesNotContain("messageSelectionActionOrder")
        assertThat(inbox).doesNotContain("MessageSelectionAction")
        assertThat(inbox).doesNotContain("PillConfig")
        val inboxScreen = source("ui/inbox/InboxScreen.kt")
        assertThat(inboxScreen).doesNotContain("messageSelectionActionOrder")
        assertThat(inboxScreen).doesNotContain("selectionActionOrder")
        // Only the conversation layout and the Settings surfaces know the preference.
        val readers =
            File("src/main/kotlin/app/clearsms/ui")
                .walk()
                .filter { it.extension == "kt" && "messageSelectionActionOrder" in it.readText() }
                .map { it.name }
                .toList()
        assertThat(readers).containsExactly("ConversationViewModel.kt", "SettingsViewModel.kt")
    }
}
