package app.clearsms.ui.finance

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * The manual "Set card limit" feature is REMOVED: the total limit now comes
 * only from issuer SMS (see TotalLimitExtractionTest). These tests pin the
 * removal at source level - no orphaned affordance, string, or unused API -
 * and the honest-display rules when no total is known: available limit only,
 * no fabricated outstanding, no meaningless 0% bar, no high-usage banner.
 */
class NoManualCardLimitTest {
    private fun sources(dir: String): List<File> =
        File("src/main/$dir")
            .walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .toList()

    @Test
    fun `no set-card-limit affordance, string or API remains anywhere in main sources`() {
        val offenders =
            sources("kotlin/app/clearsms")
                .plus(sources("res/values"))
                .filter { file ->
                    val text = file.readText()
                    text.contains("setCardLimit") ||
                        text.contains("SetCardLimitDialog") ||
                        text.contains("setCreditLimit") ||
                        text.contains("finance_set_card_limit") ||
                        text.contains("Set card limit")
                }
        assertWithMessage("manual card-limit entry must leave no trace: $offenders")
            .that(offenders)
            .isEmpty()
    }

    @Test
    fun `a card with available limit only shows it and hides outstanding and the bar`() {
        val figures = CreditCardFigures.compute(availableLimit = 97_500.0, lastKnownBalance = null, totalLimit = null)
        assertThat(CreditCardFigures.headline(figures)).isEqualTo(CardHeadline.AvailableLimit(97_500.0))
        assertThat(figures.outstanding).isNull()
        assertThat(figures.utilization).isNull()
    }

    @Test
    fun `utilization and outstanding stay fully derived when SMS supplies the total`() {
        val figures = CreditCardFigures.compute(availableLimit = 30_000.0, lastKnownBalance = null, totalLimit = 150_000.0)
        assertThat(figures.outstanding).isEqualTo(120_000.0)
        assertThat(figures.utilization).isEqualTo(0.8f)
        assertThat(figures.level).isEqualTo(UtilizationLevel.DANGER)
    }

    @Test
    fun `high-usage banner is suppressed for cards without a known total`() {
        // Heavily used card, but no total known: utilization is null and the
        // card must NOT count toward the "high credit usage" alert banner.
        val unknownTotal = CreditCardFigures.compute(availableLimit = 1_000.0, lastKnownBalance = null, totalLimit = null)
        assertThat(Utilization.countAboveSafeLimit(listOf(unknownTotal.utilization))).isEqualTo(0)

        // The same card WITH a total counts as before.
        val knownTotal = CreditCardFigures.compute(availableLimit = 1_000.0, lastKnownBalance = null, totalLimit = 100_000.0)
        assertThat(Utilization.countAboveSafeLimit(listOf(knownTotal.utilization))).isEqualTo(1)
    }
}
