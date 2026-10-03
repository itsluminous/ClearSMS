package app.clearsms.ui.components

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import app.clearsms.testing.DefaultStrings

/**
 * Contract for the muted glyph. The repo deliberately has no Compose UI
 * test infrastructure (same style as `AlwaysSortAsMenuContractTest`), so
 * this pins the SOURCE: the inbox row and the conversation title bar both
 * draw the ONE shared [MutedIndicatorIcon] - same icon, same TalkBack label
 * ([app.clearsms.R.string.inbox_muted]) - and neither keeps a private copy
 * that could drift. It also pins the title-bar geometry that keeps a long
 * sender name from pushing the glyph (and nothing else) off the bar.
 */
class MutedIndicatorContractTest {
    private fun source(path: String) = File("src/main/kotlin/app/clearsms/$path").readText()

    private fun mainSources(): Sequence<File> = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }

    @Test
    fun `exactly one definition of the glyph, and it carries the one shared label`() {
        val definitions = mainSources().filter { "fun MutedIndicatorIcon(" in it.readText() }.map { it.name }.toList()
        assertThat(definitions).containsExactly("MutedIndicator.kt")
        val component = source("ui/components/MutedIndicator.kt")
        assertThat(component).contains("Icons.Outlined.NotificationsOff")
        assertThat(component).contains("contentDescription = stringResource(R.string.inbox_muted)")
        // The label reads as a reason, not a bare state.
        val strings = DefaultStrings.ui
        assertThat(strings).contains("<string name=\"inbox_muted\">Muted - no notifications</string>")
    }

    @Test
    fun `the inbox row draws the shared glyph and no private copy`() {
        val inbox = source("ui/inbox/InboxScreen.kt")
        assertThat(inbox).contains("MutedIndicatorIcon(size = MutedIndicator.InboxRowSize)")
        // No second bell with its own description anywhere in the inbox: the
        // overflow's Mute/Unmute entries carry contentDescription = null (the
        // menu text is their label), so the only labelled bell is the shared one.
        assertThat(inbox).doesNotContain("contentDescription = stringResource(R.string.inbox_muted)")
        // The row's gate is the same normalized-set lookup the notifier uses.
        assertThat(inbox).contains("muted = state.isMuted(item),")
    }

    @Test
    fun `a placeholder row never reads a mute state`() {
        // Placeholders are on (GitHub #53): items[index] is null while a
        // page loads. The mute lookup must sit AFTER the null guard so a
        // placeholder neither crashes on a missing sender nor flashes a
        // glyph it cannot yet know about.
        val rows = source("ui/inbox/InboxScreen.kt").substringAfter("val item = items[index]")
        val guard =
            rows.indexOf(
                "if (item == null) {\n                            PagedRowPlaceholder()\n                            return@items\n                        }",
            )
        val mute = rows.indexOf("muted = state.isMuted(item),")
        assertThat(guard).isGreaterThan(-1)
        assertThat(mute).isGreaterThan(guard)
        // And isMuted itself takes a loaded item, never a nullable one.
        assertThat(source("ui/inbox/InboxViewModel.kt")).contains("fun isMuted(item: InboxItem): Boolean")
    }

    @Test
    fun `the muted set is presentational and stays out of the pager query key`() {
        // A mute toggle must re-render loaded rows in place. Putting the set
        // in InboxPagerKey (or anywhere upstream of the flatMapLatest) would
        // rebuild the Pager on every toggle and bring back the scroll reset
        // GitHub #53 fixed.
        assertThat(source("ui/inbox/InboxPagerKey.kt")).doesNotContain("muted")
        val vm = source("ui/inbox/InboxViewModel.kt")
        val pager = vm.substringAfter("val pagedItems: Flow<PagingData<InboxItem>> =").substringBefore(".cachedIn(viewModelScope)")
        assertThat(pager).doesNotContain("muted")
        // It rides the downstream chrome → uiState path instead.
        assertThat(vm).contains(".combine(settings.mutedSenders) { chrome, muted -> chrome.copy(mutedSenders = muted) }")
        assertThat(vm).contains("mutedSenders = chromeState.mutedSenders,")
    }

    @Test
    fun `the conversation title bar draws the shared glyph beside the name, from live state`() {
        val conversation = source("ui/conversation/ConversationScreen.kt")
        val titleSlot =
            conversation.substring(
                conversation.indexOf("TopAppBar(\n                    title = {"),
                conversation.indexOf("navigationIcon = {"),
            )
        assertThat(titleSlot).contains("if (state.muted) {")
        assertThat(titleSlot).contains("MutedIndicatorIcon(size = MutedIndicator.TitleBarSize)")
        // Beside the name, after it: avatar, name, glyph.
        assertThat(titleSlot.indexOf("text = state.title,")).isLessThan(titleSlot.indexOf("MutedIndicatorIcon("))
        assertThat(titleSlot.indexOf("SenderAvatar(")).isLessThan(titleSlot.indexOf("text = state.title,"))
        // No private copy in the bar either.
        assertThat(conversation).doesNotContain("contentDescription = stringResource(R.string.inbox_muted)")
        // The glyph's source of truth is uiState.muted, which the view model
        // derives from settings.mutedSenders in the same combine that feeds
        // the title - so an overflow Mute/Unmute redraws it without a reload.
        val viewModel = source("ui/conversation/ConversationViewModel.kt")
        assertThat(viewModel).contains("settings.mutedSenders,")
        assertThat(viewModel).contains("muted = first?.sender?.let { MutedSenderGate.matches(mutedSenders, it) } ?: false,")
    }

    @Test
    fun `the title-bar glyph matches the action icons and the inbox glyph is unchanged`() {
        // The operator's screenshot: an 18dp bell beside a 24dp phone read
        // as an afterthought. The bar glyph now matches Material's default
        // Icon size - the size the Call and overflow glyphs are drawn at.
        assertThat(MutedIndicator.ActionIconSize).isEqualTo(24.dp)
        assertThat(MutedIndicator.TitleBarSize).isEqualTo(MutedIndicator.ActionIconSize)
        // ...and the action icons really are at Material's default: the
        // shared button wrapper passes no size modifier to its Icon.
        val button = source("ui/components/TooltipIconButton.kt")
        assertThat(button).contains("Icon(icon, contentDescription = label, tint = tint ?: LocalContentColor.current)")
        assertThat(button).doesNotContain(".size(")
        // The inbox glyph sits beside a timestamp in a text row - a
        // different context, deliberately NOT touched.
        assertThat(MutedIndicator.InboxRowSize).isEqualTo(14.dp)
        // Still an indicator, not a button: no click target, no ripple, and
        // the one shared label.
        val component = source("ui/components/MutedIndicator.kt")
        assertThat(component).doesNotContain("clickable")
        assertThat(component).doesNotContain("IconButton")
        assertThat(component).doesNotContain("indication")
        assertThat(component).contains("modifier = modifier.size(size),")
    }

    @Test
    fun `a long sender name wraps to two lines and ellipsises inside the title slot instead of pushing the glyph off the bar`() {
        val conversation = source("ui/conversation/ConversationScreen.kt")
        val titleSlot =
            conversation.substring(
                conversation.indexOf("TopAppBar(\n                    title = {"),
                conversation.indexOf("navigationIcon = {"),
            )
        val nameText = titleSlot.substring(titleSlot.indexOf("text = state.title,"), titleSlot.indexOf("if (state.muted) {"))
        // Two lines, then ellipsis - the line bound comes from the ONE
        // contract object (ConversationTitleFitTest pins its value).
        assertThat(nameText).contains("maxLines = ConversationTitleFit.MaxLines,")
        assertThat(nameText).doesNotContain("maxLines = 1")
        assertThat(nameText).contains("overflow = TextOverflow.Ellipsis,")
        // weight(fill = false): the name takes what is left AFTER the glyph
        // is measured, and a short name does not stretch the row.
        assertThat(nameText).contains(".weight(1f, fill = false)")
        // The bar's other occupants are untouched: call button, tap-the-name
        // contact action, and the overflow all still there. Change category
        // is NOT a bar icon any more - it lives in the overflow (see
        // `change category lives in the overflow, not the bar` below).
        val bar =
            conversation.substring(
                conversation.indexOf("TopAppBar(\n                    title = {"),
                conversation.indexOf("snackbarHost = {"),
            )
        assertThat(bar).contains("SenderContactAction.onNameTap(state.address, state.contactLookupUri)")
        assertThat(bar).contains("icon = Icons.Outlined.Call,")
        assertThat(bar).contains("label = stringResource(R.string.action_more_options),")
        assertThat(bar).doesNotContain("label = stringResource(R.string.action_change_category),")
    }

    @Test
    fun `change category lives in the overflow, not the bar, and still opens the one shared dialog`() {
        val conversation = source("ui/conversation/ConversationScreen.kt")
        val actions =
            conversation.substring(
                conversation.indexOf("actions = {\n                        if (dialableSender != null) {"),
                conversation.indexOf("snackbarHost = {"),
            )
        // Not a TooltipIconButton in the bar: the only bar-level buttons are
        // Call and More options. (The moved icon was a rarely used action.)
        val barButtons =
            Regex("TooltipIconButton\\(\\s*label = stringResource\\(R\\.string\\.(\\w+)\\)")
                .findAll(actions)
                .map { it.groupValues[1] }
                .toList()
        assertThat(barButtons).containsExactly("conversation_call", "action_more_options").inOrder()
        // It IS a DropdownMenuItem inside the overflow, with the existing
        // label string as its text (the menu text is its TalkBack label).
        val menu = actions.substring(actions.indexOf("DropdownMenu(expanded = menuOpen"))
        val items =
            Regex("DropdownMenuItem\\(\\s*text = \\{[^}]*?R\\.string\\.(\\w+)")
                .findAll(menu)
                .map { it.groupValues[1] }
                .toList()
        // Order: the state toggle first (its position is unchanged), the
        // rarely used Change category last - as "Always sort as…" is last in
        // the inbox selection overflow.
        assertThat(items).containsExactly("action_unmute_sender", "action_change_category").inOrder()
        assertThat(menu).contains("text = { Text(stringResource(R.string.action_change_category)) },")
        // Same entry point as before: it flips the flag that shows the ONE
        // shared SenderRuleDialog, no second dialog or save path.
        assertThat(menu).contains("changeCategoryOpen = true")
        assertThat(conversation.split("changeCategoryOpen = true").size - 1).isEqualTo(1)
        assertThat(conversation).contains("if (changeCategoryOpen && state.address.isNotBlank()) {\n        SenderRuleDialog(")
        // Same gate as the icon had: the whole overflow (button + menu) sits
        // under the address check, so a blank-address thread renders neither
        // an empty menu nor a dangling More button.
        val gate = actions.substring(actions.indexOf("if (state.address.isNotBlank()) {"))
        assertThat(gate.indexOf("label = stringResource(R.string.action_more_options),")).isGreaterThan(-1)
        assertThat(gate.indexOf("R.string.action_change_category")).isGreaterThan(gate.indexOf("DropdownMenu(expanded = menuOpen"))
        // The label string itself is unchanged user-visible text.
        val strings = DefaultStrings.ui
        assertThat(strings).contains("<string name=\"action_change_category\">Change category</string>")
    }
}
