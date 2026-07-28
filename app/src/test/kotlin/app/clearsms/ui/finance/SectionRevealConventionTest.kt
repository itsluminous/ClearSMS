package app.clearsms.ui.finance

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Source-level invariants for the de-cramped Finance rows (in the spirit of
 * BalancePrivacyConventionTest):
 *
 * 1. The reveal eye is SCREEN-level — exactly one [BalanceEyeButton] per
 *    finance screen (in the top bar), never one per row. The reveal state
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
    fun `finance screen has exactly one screen-level eye and no per-row eyes`() {
        val text = source("ui/finance/FinanceScreen.kt")
        val eyes = Regex("BalanceEyeButton\\(").findAll(text).count()
        assertWithMessage("FinanceScreen must host exactly ONE reveal eye (top bar)")
            .that(eyes)
            .isEqualTo(1)
        assertWithMessage("rows must use the eye-free MaskedAmountText")
            .that(text)
            .contains("MaskedAmountText(")
    }

    @Test
    fun `account detail has exactly one screen-level eye`() {
        val text = source("ui/finance/AccountDetailScreen.kt")
        assertWithMessage("AccountDetailScreen must host exactly ONE reveal eye (top bar)")
            .that(Regex("BalanceEyeButton\\(").findAll(text).count())
            .isEqualTo(1)
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
