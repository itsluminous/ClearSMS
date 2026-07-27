package app.clearsms.ui.finance

import app.clearsms.data.db.TransactionEntity
import app.clearsms.domain.model.TransactionType
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One month's debit/credit totals for the hand-rolled bar chart. */
data class MonthlyTotals(
    val month: YearMonth,
    val debits: Double,
    val credits: Double,
) {
    val label: String get() = month.format(MONTH_FORMAT)

    /** "June 2026" — used by the selection details row and bar accessibility labels. */
    val fullLabel: String get() = month.format(FULL_MONTH_FORMAT)

    private companion object {
        val MONTH_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)
        val FULL_MONTH_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
    }
}

/** Pure aggregation of transactions into month buckets (chart + list headers). */
object MonthlyAggregation {
    /**
     * Totals for the last [months] calendar months ending at [endMonth],
     * oldest first; months with no transactions produce zero rows.
     */
    fun lastMonths(
        transactions: List<TransactionEntity>,
        months: Int,
        endMonth: YearMonth,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<MonthlyTotals> {
        val byMonth = transactions.groupBy { YearMonth.from(Instant.ofEpochMilli(it.timestamp).atZone(zone)) }
        return (months - 1 downTo 0).map { back ->
            val month = endMonth.minusMonths(back.toLong())
            val txs = byMonth[month].orEmpty()
            MonthlyTotals(
                month = month,
                debits = txs.filter { it.type == TransactionType.DEBIT }.sumOf { it.amount },
                credits = txs.filter { it.type == TransactionType.CREDIT }.sumOf { it.amount },
            )
        }
    }

    /** Transactions grouped by calendar month, newest month first, order inside preserved. */
    fun groupByMonth(
        transactions: List<TransactionEntity>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Pair<YearMonth, List<TransactionEntity>>> =
        transactions
            .groupBy { YearMonth.from(Instant.ofEpochMilli(it.timestamp).atZone(zone)) }
            .toList()
            .sortedByDescending { it.first }

    /** Net amount (credits − debits) across [transactions]. */
    fun net(transactions: List<TransactionEntity>): Double =
        transactions.sumOf { if (it.type == TransactionType.CREDIT) it.amount else -it.amount }
}
