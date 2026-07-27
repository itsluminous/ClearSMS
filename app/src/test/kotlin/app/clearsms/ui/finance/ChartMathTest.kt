package app.clearsms.ui.finance

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.YearMonth

class ChartMathTest {
    private fun month(
        debits: Double,
        credits: Double,
    ) = MonthlyTotals(YearMonth.of(2026, 6), debits, credits)

    @Test
    fun `x offsets map to month indices across the plot width`() {
        // 600px plot, 6 months → 100px per group.
        assertThat(ChartMath.monthIndex(x = 0f, plotWidth = 600f, monthCount = 6)).isEqualTo(0)
        assertThat(ChartMath.monthIndex(x = 99.9f, plotWidth = 600f, monthCount = 6)).isEqualTo(0)
        assertThat(ChartMath.monthIndex(x = 100f, plotWidth = 600f, monthCount = 6)).isEqualTo(1)
        assertThat(ChartMath.monthIndex(x = 350f, plotWidth = 600f, monthCount = 6)).isEqualTo(3)
        assertThat(ChartMath.monthIndex(x = 599.9f, plotWidth = 600f, monthCount = 6)).isEqualTo(5)
    }

    @Test
    fun `taps outside the plot hit nothing`() {
        assertThat(ChartMath.monthIndex(x = -1f, plotWidth = 600f, monthCount = 6)).isNull()
        assertThat(ChartMath.monthIndex(x = 600f, plotWidth = 600f, monthCount = 6)).isNull()
        assertThat(ChartMath.monthIndex(x = 10f, plotWidth = 600f, monthCount = 0)).isNull()
        assertThat(ChartMath.monthIndex(x = 10f, plotWidth = 0f, monthCount = 6)).isNull()
    }

    @Test
    fun `single month occupies the whole plot`() {
        assertThat(ChartMath.monthIndex(x = 1f, plotWidth = 600f, monthCount = 1)).isEqualTo(0)
        assertThat(ChartMath.monthIndex(x = 599f, plotWidth = 600f, monthCount = 1)).isEqualTo(0)
    }

    @Test
    fun `max value never returns zero for all-zero data`() {
        assertThat(ChartMath.maxValue(emptyList())).isEqualTo(1.0)
        assertThat(ChartMath.maxValue(listOf(month(0.0, 0.0), month(0.0, 0.0)))).isEqualTo(1.0)
    }

    @Test
    fun `max value spans both debit and credit series`() {
        val data = listOf(month(500.0, 12_000.0), month(45_300.0, 0.0))
        assertThat(ChartMath.maxValue(data)).isEqualTo(45_300.0)
    }

    @Test
    fun `very large values scale without overflow`() {
        val data = listOf(month(9_99_99_99_999.0, 0.0))
        assertThat(ChartMath.maxValue(data)).isEqualTo(9_99_99_99_999.0)
    }
}
