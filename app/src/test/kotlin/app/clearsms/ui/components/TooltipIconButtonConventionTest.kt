package app.clearsms.ui.components

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Source-level convention for icon-only bar buttons (in the spirit of
 * ComposerBarContractTest / SectionRevealConventionTest):
 *
 * 1. Top-bar / selection-bar / action icon buttons in the converted screens
 *    render through [TooltipIconButton], so long-pressing them reveals
 *    their label (standard Android behaviour) - never a raw [IconButton]
 *    outside the documented allowlist.
 * 2. The wrapper takes ONE label that feeds BOTH the tooltip text and the
 *    icon's contentDescription - the visible label and the TalkBack label
 *    cannot diverge.
 * 3. The compose bar's Send button is the deliberate EXCEPTION: it carries
 *    a real long-press action (long-press-to-schedule) that a tooltip's
 *    long-press would swallow, so it must keep its combinedClickable and
 *    never gain a TooltipBox.
 */
class TooltipIconButtonConventionTest {
    private val srcRoot = File("src/main/kotlin/app/clearsms")

    private fun source(path: String): String {
        val file = File(srcRoot, path)
        assertWithMessage("expected source file $path").that(file.isFile).isTrue()
        return file.readText()
    }

    /** Raw IconButton( calls, excluding TooltipIconButton( and imports. */
    private fun rawIconButtonCount(text: String): Int =
        text
            .lineSequence()
            .filterNot { it.trimStart().startsWith("import ") }
            .sumOf { line -> Regex("(?<![A-Za-z])IconButton\\(").findAll(line).count() }

    /**
     * Converted files → allowed RAW IconButton count. Non-zero entries are
     * the allowlist: list-item buttons are out of the tooltip sweep's scope
     * (the convention covers bars and standalone actions).
     */
    private val convertedFiles =
        mapOf(
            "ui/navigation/TopBarActions.kt" to 0,
            "ui/inbox/InboxScreen.kt" to 0,
            "ui/conversation/ConversationScreen.kt" to 0,
            // Per-rule delete lives on a list row - out of scope.
            "ui/rules/RulesScreen.kt" to 1,
            "ui/search/SearchScreen.kt" to 0,
            "ui/settings/SettingsScreen.kt" to 0,
            // The picked-recipient row's change-recipient X is a list-item
            // affordance - out of scope.
            "ui/composemsg/ComposeMessageScreen.kt" to 1,
            "ui/components/MessageComposerBar.kt" to 0,
        )

    @Test
    fun `converted bar files render icon-only buttons through the tooltip wrapper`() {
        convertedFiles.forEach { (path, allowedRaw) ->
            val text = source(path)
            assertWithMessage("$path: raw IconButton( outside the allowlist - use TooltipIconButton")
                .that(rawIconButtonCount(text))
                .isEqualTo(allowedRaw)
            assertWithMessage("$path: expected at least one TooltipIconButton")
                .that(text)
                .contains("TooltipIconButton(")
        }
    }

    @Test
    fun `wrapper feeds ONE label to both the tooltip and the contentDescription`() {
        val wrapper = source("ui/components/TooltipIconButton.kt")
        assertWithMessage("tooltip text renders the label")
            .that(wrapper)
            .contains("PlainTooltip { Text(label) }")
        assertWithMessage("the SAME label is the accessibility description")
            .that(wrapper)
            .contains("contentDescription = label")
        assertWithMessage("no separate contentDescription parameter may exist")
            .that(wrapper)
            .doesNotContain("contentDescription: String")
    }

    @Test
    fun `send button keeps its real long-press-to-schedule - no tooltip may swallow it`() {
        val bar = source("ui/components/MessageComposerBar.kt")
        assertWithMessage("send keeps the combined tap/long-press surface")
            .that(bar)
            .contains("combinedClickable(")
        assertWithMessage("send's long-press still opens the schedule picker")
            .that(bar)
            .contains("onScheduleSend")
        assertWithMessage("no TooltipBox may wrap the send surface (its long-press is schedule)")
            .that(bar)
            .doesNotContain("TooltipBox(")
    }
}
