package app.clearsms.ui.finance

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UtilizationTest {
    @Test
    fun `fraction is null without a positive limit`() {
        assertThat(Utilization.fraction(5000.0, null)).isNull()
        assertThat(Utilization.fraction(5000.0, 0.0)).isNull()
        assertThat(Utilization.fraction(5000.0, -1.0)).isNull()
    }

    @Test
    fun `fraction is clamped to 0-1`() {
        assertThat(Utilization.fraction(150_000.0, 100_000.0)).isEqualTo(1f)
        assertThat(Utilization.fraction(-10.0, 100_000.0)).isEqualTo(0f)
        assertThat(Utilization.fraction(25_000.0, 100_000.0)).isEqualTo(0.25f)
    }

    @Test
    fun `level steps at 50 and 80 percent`() {
        assertThat(Utilization.level(0.0f)).isEqualTo(UtilizationLevel.NORMAL)
        assertThat(Utilization.level(0.49f)).isEqualTo(UtilizationLevel.NORMAL)
        assertThat(Utilization.level(0.50f)).isEqualTo(UtilizationLevel.WARNING)
        assertThat(Utilization.level(0.79f)).isEqualTo(UtilizationLevel.WARNING)
        assertThat(Utilization.level(0.80f)).isEqualTo(UtilizationLevel.DANGER)
        assertThat(Utilization.level(1.0f)).isEqualTo(UtilizationLevel.DANGER)
    }

    @Test
    fun `cards above the 30 percent safe limit are counted`() {
        val fractions = listOf(0.31f, 0.29f, null, 0.90f, 0.30f)
        assertThat(Utilization.countAboveSafeLimit(fractions)).isEqualTo(2)
    }
}
