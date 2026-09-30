package app.clearsms.ui.finance

import app.clearsms.data.db.TransactionEntity
import app.clearsms.domain.model.MerchantCategory
import app.clearsms.domain.model.TransactionType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * Money in different currencies is NEVER added together (issue #65): every
 * aggregate the Finance screens show is in one currency, and rows in other
 * currencies are reported as a count beside it.
 */
class MixedCurrencyAggregationTest {
    private var nextId = 1L

    private fun tx(
        amount: Double,
        currency: String,
        type: TransactionType = TransactionType.DEBIT,
        at: Long = 1_700_000_000_000 + nextId,
    ): TransactionEntity {
        val id = nextId++
        return TransactionEntity(
            id = id,
            amount = amount,
            currency = currency,
            type = type,
            merchantName = "Merchant",
            accountNumber = "1234",
            bankName = "Bank",
            timestamp = at,
            category = MerchantCategory.OTHER,
            rawSmsId = id,
        )
    }

    private val noCards = MonthSummary.CardIdentity(emptySet(), emptySet())

    @Test
    fun `month totals are in the dominant currency and count the rest`() {
        val totals =
            MonthSummary.compute(
                listOf(
                    tx(1000.0, "CLP"),
                    tx(2500.0, "CLP"),
                    tx(500.0, "CLP", TransactionType.CREDIT),
                    tx(40.95, "USD"),
                ),
                noCards,
            )
        assertThat(totals.currency).isEqualTo("CLP")
        assertThat(totals.debits).isEqualTo(3500.0)
        assertThat(totals.credits).isEqualTo(500.0)
        assertThat(totals.net).isEqualTo(-3000.0)
        assertThat(totals.txCount).isEqualTo(3)
        // The dollar spend is reported, never folded into the peso figures.
        assertThat(totals.otherCurrencyCount).isEqualTo(1)
    }

    @Test
    fun `a tie on row count goes to the most recent currency`() {
        val totals =
            MonthSummary.compute(
                listOf(tx(100.0, "INR", at = 1_000L), tx(100.0, "USD", at = 2_000L)),
                noCards,
            )
        assertThat(totals.currency).isEqualTo("USD")
        assertThat(totals.otherCurrencyCount).isEqualTo(1)
    }

    @Test
    fun `an all-rupee month is unchanged - INR with nothing left out`() {
        val totals = MonthSummary.compute(listOf(tx(100.0, "INR"), tx(50.0, "INR", TransactionType.CREDIT)), noCards)
        assertThat(totals.currency).isEqualTo("INR")
        assertThat(totals.otherCurrencyCount).isEqualTo(0)
        assertThat(totals.net).isEqualTo(-50.0)
        assertThat(MonthSummary.compute(emptyList(), noCards).currency).isEqualTo("INR")
    }

    @Test
    fun `chart bars and net sum one currency only`() {
        val month = YearMonth.of(2026, 9)
        val at =
            month
                .atDay(10)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        val rows = listOf(tx(1000.0, "CLP", at = at), tx(2000.0, "CLP", at = at + 1), tx(99.0, "USD", at = at + 2))
        val bars = MonthlyAggregation.lastMonths(rows, months = 1, endMonth = month, zone = ZoneOffset.UTC)
        assertThat(bars.single().debits).isEqualTo(3000.0)
        assertThat(bars.single().currency).isEqualTo("CLP")
        assertThat(bars.single().otherCurrencyCount).isEqualTo(1)
        assertThat(MonthlyAggregation.net(rows)).isEqualTo(-3000.0)
        assertThat(Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC).month).isEqualTo(month.month)
    }
}
