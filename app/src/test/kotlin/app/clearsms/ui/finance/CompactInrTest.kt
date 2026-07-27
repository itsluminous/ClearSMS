package app.clearsms.ui.finance

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CompactInrTest {
    @Test
    fun `values under a thousand are whole rupees`() {
        assertThat(CompactInr.format(0.0)).isEqualTo("₹0")
        assertThat(CompactInr.format(450.0)).isEqualTo("₹450")
        assertThat(CompactInr.format(999.0)).isEqualTo("₹999")
    }

    @Test
    fun `thousands use k with at most one decimal`() {
        assertThat(CompactInr.format(1_000.0)).isEqualTo("₹1k")
        assertThat(CompactInr.format(1_234.0)).isEqualTo("₹1.2k")
        assertThat(CompactInr.format(45_000.0)).isEqualTo("₹45k")
        assertThat(CompactInr.format(99_949.0)).isEqualTo("₹99.9k")
    }

    @Test
    fun `lakh boundary promotes instead of showing 100k`() {
        assertThat(CompactInr.format(99_950.0)).isEqualTo("₹1L")
        assertThat(CompactInr.format(1_20_000.0)).isEqualTo("₹1.2L")
        assertThat(CompactInr.format(45_00_000.0)).isEqualTo("₹45L")
    }

    @Test
    fun `crore boundary promotes instead of showing 100L`() {
        assertThat(CompactInr.format(99_95_000.0)).isEqualTo("₹1Cr")
        assertThat(CompactInr.format(1_00_00_000.0)).isEqualTo("₹1Cr")
        assertThat(CompactInr.format(2_35_00_000.0)).isEqualTo("₹2.4Cr")
    }

    @Test
    fun `rounding is half up within a unit`() {
        assertThat(CompactInr.format(1_250.0)).isEqualTo("₹1.3k")
        assertThat(CompactInr.format(1_240.0)).isEqualTo("₹1.2k")
        assertThat(CompactInr.format(999.4)).isEqualTo("₹999")
        assertThat(CompactInr.format(999.5)).isEqualTo("₹1k")
    }

    @Test
    fun `negative values keep a leading minus`() {
        assertThat(CompactInr.format(-1_500.0)).isEqualTo("-₹1.5k")
        assertThat(CompactInr.format(-500.0)).isEqualTo("-₹500")
    }
}
