package app.clearsms.ui.finance

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The credit-card headline and utilization rules:
 * available limit is always the preferred headline; outstanding is derived
 * (total − available) or falls back to legacy issuer-balance data; no data
 * yields a neutral state — a ₹0 balance is never fabricated.
 */
class CreditCardFiguresTest {
    @Test
    fun `available limit is the headline when known`() {
        val figures = CreditCardFigures.compute(availableLimit = 287185.45, lastKnownBalance = null, totalLimit = null)
        assertThat(CreditCardFigures.headline(figures)).isEqualTo(CardHeadline.AvailableLimit(287185.45))
    }

    @Test
    fun `no data yields the neutral state - never a zero balance`() {
        val figures = CreditCardFigures.compute(availableLimit = null, lastKnownBalance = null, totalLimit = null)
        assertThat(CreditCardFigures.headline(figures)).isEqualTo(CardHeadline.NoData)
        assertThat(figures.outstanding).isNull()
        assertThat(figures.utilization).isNull()
    }

    @Test
    fun `no data with a user-set total limit still yields the neutral state`() {
        // The old code fed outstanding = balance ?: 0.0 into the bar — a
        // fabricated 0% utilization from a zero balance. No data means no bar.
        val figures = CreditCardFigures.compute(availableLimit = null, lastKnownBalance = null, totalLimit = 100_000.0)
        assertThat(CreditCardFigures.headline(figures)).isEqualTo(CardHeadline.NoData)
        assertThat(figures.utilization).isNull()
    }

    @Test
    fun `legacy issuer balance falls back to an outstanding headline`() {
        val figures = CreditCardFigures.compute(availableLimit = null, lastKnownBalance = 12_500.0, totalLimit = null)
        assertThat(CreditCardFigures.headline(figures)).isEqualTo(CardHeadline.Outstanding(12_500.0))
    }

    @Test
    fun `outstanding is total minus available when both are known`() {
        val figures = CreditCardFigures.compute(availableLimit = 287185.45, lastKnownBalance = null, totalLimit = 300_000.0)
        assertThat(figures.outstanding).isEqualTo(300_000.0 - 287185.45)
        assertThat(figures.utilization).isWithin(1e-6f).of(((300_000.0 - 287185.45) / 300_000.0).toFloat())
        assertThat(figures.level).isEqualTo(UtilizationLevel.NORMAL)
    }

    @Test
    fun `available above the total clamps outstanding at zero`() {
        // The user lowered the stored limit (or the issuer raised headroom):
        // usage can never go negative.
        val figures = CreditCardFigures.compute(availableLimit = 120_000.0, lastKnownBalance = null, totalLimit = 100_000.0)
        assertThat(figures.outstanding).isEqualTo(0.0)
        assertThat(figures.utilization).isEqualTo(0f)
    }

    @Test
    fun `zero or missing total limit never divides - utilization stays null`() {
        val zero = CreditCardFigures.compute(availableLimit = 50_000.0, lastKnownBalance = null, totalLimit = 0.0)
        assertThat(zero.outstanding).isNull()
        assertThat(zero.utilization).isNull()

        val missing = CreditCardFigures.compute(availableLimit = 50_000.0, lastKnownBalance = null, totalLimit = null)
        assertThat(missing.outstanding).isNull()
        assertThat(missing.utilization).isNull()
        // The headline is still the available limit — it needs no total.
        assertThat(CreditCardFigures.headline(missing)).isEqualTo(CardHeadline.AvailableLimit(50_000.0))
    }

    @Test
    fun `legacy balance is ignored once an available limit exists`() {
        // The semantics differ: a stale issuer balance must never distort
        // outstanding derived from the fresher available limit.
        val figures = CreditCardFigures.compute(availableLimit = 80_000.0, lastKnownBalance = 5.0, totalLimit = 100_000.0)
        assertThat(figures.outstanding).isEqualTo(20_000.0)
        assertThat(CreditCardFigures.headline(figures)).isEqualTo(CardHeadline.AvailableLimit(80_000.0))
    }

    @Test
    fun `utilization levels step from the derived outstanding`() {
        val warning = CreditCardFigures.compute(availableLimit = 40_000.0, lastKnownBalance = null, totalLimit = 100_000.0)
        assertThat(warning.level).isEqualTo(UtilizationLevel.WARNING)

        val danger = CreditCardFigures.compute(availableLimit = 10_000.0, lastKnownBalance = null, totalLimit = 100_000.0)
        assertThat(danger.level).isEqualTo(UtilizationLevel.DANGER)
    }
}
