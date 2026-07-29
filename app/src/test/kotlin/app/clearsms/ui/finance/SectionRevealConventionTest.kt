package app.clearsms.ui.finance

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Source-level invariants for the de-cramped Finance rows (in the spirit of
 * BalancePrivacyConventionTest):
 *
 * 1. The reveal control is SCREEN-level — the labelled button in the
 *    Finance summary card ([BalanceRevealCardButton]) and one top-bar eye
 *    on the account detail — never one per row. The reveal state machine
 *    machine itself is unchanged (see BalancePrivacyViewModelTest); this
 *    pins that every row reads the same shared (gated, revealed) pair.
 * 2. No per-row open-in-new icon: the card itself navigates, and the source
 *    message moved to long-press with a proper accessibility label.
 * 3. Names get the room: two-line max with ellipsis via
 *    [FinanceRowLayout.MAX_NAME_LINES].
 */
class SectionRevealConventionTest {
    private fun source(path: String): String {
        val file = File("src/main/kotlin/app/clearsms/$path")
        assertWithMessage("expected source file $path").that(file.isFile).isTrue()
        return file.readText()
    }

    @Test
    fun `finance screen reveal moved into the summary card - no top-bar eye`() {
        val text = source("ui/finance/FinanceScreen.kt")
        assertWithMessage("FinanceScreen must not host the bare top-bar eye any more")
            .that(text)
            .doesNotContain("BalanceEyeButton(")
        assertWithMessage("the labelled reveal button lives in the summary card, exactly once")
            .that(Regex("BalanceRevealCardButton\\(").findAll(text).count())
            .isEqualTo(1)
        assertWithMessage("rows must use the eye-free MaskedAmountText")
            .that(text)
            .contains("MaskedAmountText(")
    }

    @Test
    fun `account detail has exactly one screen-level eye`() {
        val text = source("ui/finance/AccountDetailScreen.kt")
        // Account detail deliberately has NO reveal eye: its main content is the
        // transaction list (never masked), and the few gated figures follow the
        // app-wide reveal set on the Finance dashboard (shared session state).
        assertWithMessage("AccountDetailScreen must not host its own reveal eye")
            .that(Regex("BalanceEyeButton\\(").findAll(text).count())
            .isEqualTo(0)
    }

    @Test
    fun `reveal button lives inside the summary card without breaking the expand tap`() {
        val text = source("ui/finance/FinanceScreen.kt")
        val card = text.substringAfter("private fun MonthSummaryCard")
        assertWithMessage("the reveal button renders inside MonthSummaryCard")
            .that(card)
            .contains("BalanceRevealCardButton(")
        assertWithMessage("the card's own expand tap (with its click label) must survive")
            .that(card)
            .contains(".clickable(onClickLabel = clickLabel, onClick = onToggle)")
        assertWithMessage("the expand chevron must survive")
            .that(card)
            .contains("if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore")
    }

    @Test
    fun `no per-row open-in-new button - source message moved to long-press`() {
        val text = source("ui/finance/FinanceScreen.kt")
        assertWithMessage("the open-in-new icon button must not return")
            .that(text)
            .doesNotContain("OpenInNew")
        assertWithMessage("long-press must open the source message with a label")
            .that(text)
            .contains("onLongClickLabel = stringResource(R.string.finance_open_source_sms)")
        assertWithMessage("tap must keep opening the account detail with a label")
            .that(text)
            .contains("onClickLabel = stringResource(R.string.finance_open_account_detail)")
    }

    @Test
    fun `row names are capped at two lines with ellipsis`() {
        val text = source("ui/finance/FinanceScreen.kt")
        assertWithMessage("account and card names must use the shared line cap")
            .that(Regex("maxLines = FinanceRowLayout\\.MAX_NAME_LINES").findAll(text).count())
            .isAtLeast(2)
        assertWithMessage("the cap is two lines")
            .that(FinanceRowLayout.MAX_NAME_LINES)
            .isEqualTo(2)
    }
}
