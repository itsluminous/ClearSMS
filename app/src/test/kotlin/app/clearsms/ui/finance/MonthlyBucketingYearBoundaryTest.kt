package app.clearsms.ui.finance

import app.clearsms.data.db.TransactionEntity
import app.clearsms.domain.model.TransactionType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

/** Monthly bucketing across a December → January year boundary. */
class MonthlyBucketingYearBoundaryTest {
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
    fun `window spanning a year boundary keeps months in order`() {
        val result =
            MonthlyAggregation.lastMonths(
                transactions = emptyList(),
                months = 4,
                endMonth = YearMonth.of(2026, 2),
                zone = zone,
            )
        assertThat(result.map { it.month })
            .containsExactly(
                YearMonth.of(2025, 11),
                YearMonth.of(2025, 12),
                YearMonth.of(2026, 1),
                YearMonth.of(2026, 2),
            ).inOrder()
    }

    @Test
    fun `december and january amounts land in their own years`() {
        val transactions =
            listOf(
                tx(2025, 12, 31, 800.0, TransactionType.DEBIT),
                tx(2026, 1, 1, 1_200.0, TransactionType.DEBIT),
                tx(2026, 1, 1, 5_000.0, TransactionType.CREDIT),
            )
        val result =
            MonthlyAggregation.lastMonths(transactions, months = 3, endMonth = YearMonth.of(2026, 2), zone = zone)
        val december = result.first { it.month == YearMonth.of(2025, 12) }
        val january = result.first { it.month == YearMonth.of(2026, 1) }
        assertThat(december.debits).isEqualTo(800.0)
        assertThat(december.credits).isEqualTo(0.0)
        assertThat(january.debits).isEqualTo(1_200.0)
        assertThat(january.credits).isEqualTo(5_000.0)
    }
}
