package app.clearsms.ui.finance

import app.clearsms.data.db.TransactionEntity
import app.clearsms.domain.model.TransactionType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

class MonthlyAggregationTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    private fun tx(
        year: Int,
        month: Int,
        day: Int,
        amount: Double,
        type: TransactionType,
    ): TransactionEntity =
        TransactionEntity(
            id = 0,
            amount = amount,
            type = type,
            accountNumber = "1234",
            bankName = "Test Bank",
            timestamp =
                LocalDateTime
                    .of(year, month, day, 12, 0)
                    .atZone(zone)
                    .toInstant()
                    .toEpochMilli(),
            rawSmsId = 0,
        )

    @Test
    fun `lastMonths returns one bucket per month oldest first`() {
        val result =
            MonthlyAggregation.lastMonths(
                transactions = emptyList(),
                months = 6,
                endMonth = YearMonth.of(2026, 7),
                zone = zone,
            )
        assertThat(result).hasSize(6)
        assertThat(result.first().month).isEqualTo(YearMonth.of(2026, 2))
        assertThat(result.last().month).isEqualTo(YearMonth.of(2026, 7))
        assertThat(result.all { it.debits == 0.0 && it.credits == 0.0 }).isTrue()
    }

    @Test
    fun `debits and credits are summed into the right month`() {
        val transactions =
            listOf(
                tx(2026, 7, 1, 500.0, TransactionType.DEBIT),
                tx(2026, 7, 15, 250.0, TransactionType.DEBIT),
                tx(2026, 7, 20, 10_000.0, TransactionType.CREDIT),
                tx(2026, 6, 30, 999.0, TransactionType.DEBIT),
            )
        val result =
            MonthlyAggregation.lastMonths(transactions, months = 2, endMonth = YearMonth.of(2026, 7), zone = zone)
        val june = result[0]
        val july = result[1]
        assertThat(june.debits).isEqualTo(999.0)
        assertThat(june.credits).isEqualTo(0.0)
        assertThat(july.debits).isEqualTo(750.0)
        assertThat(july.credits).isEqualTo(10_000.0)
    }

    @Test
    fun `transactions outside the window are ignored`() {
        val transactions = listOf(tx(2025, 1, 1, 100.0, TransactionType.DEBIT))
        val result =
            MonthlyAggregation.lastMonths(transactions, months = 6, endMonth = YearMonth.of(2026, 7), zone = zone)
        assertThat(result.sumOf { it.debits }).isEqualTo(0.0)
    }

    @Test
    fun `groupByMonth sorts newest month first`() {
        val transactions =
            listOf(
                tx(2026, 5, 2, 1.0, TransactionType.DEBIT),
                tx(2026, 7, 2, 2.0, TransactionType.DEBIT),
                tx(2026, 6, 2, 3.0, TransactionType.DEBIT),
            )
        val groups = MonthlyAggregation.groupByMonth(transactions, zone)
        assertThat(groups.map { it.first })
            .containsExactly(
                YearMonth.of(2026, 7),
                YearMonth.of(2026, 6),
                YearMonth.of(2026, 5),
            ).inOrder()
    }

    @Test
    fun `net is credits minus debits`() {
        val transactions =
            listOf(
                tx(2026, 7, 1, 300.0, TransactionType.CREDIT),
                tx(2026, 7, 2, 100.0, TransactionType.DEBIT),
            )
        assertThat(MonthlyAggregation.net(transactions)).isEqualTo(200.0)
    }
}
