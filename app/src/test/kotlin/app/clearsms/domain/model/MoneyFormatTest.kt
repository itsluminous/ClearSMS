package app.clearsms.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The one formatter behind every amount the app shows. The INR sections are
 * the former CurrencyFormatTest / CompactInrTest verbatim - Indian users
 * must keep seeing exactly what they always saw.
 */
class MoneyFormatTest {
    // region INR - preserved byte for byte

    @Test
    fun `rupees use indian digit grouping`() {
        assertThat(MoneyFormat.format(123456.78, "INR")).isEqualTo("₹1,23,456.78")
        assertThat(MoneyFormat.format(1000.0, "INR")).isEqualTo("₹1,000")
        assertThat(MoneyFormat.format(10000000.0, "INR")).isEqualTo("₹1,00,00,000")
    }

    @Test
    fun `negative rupee amounts carry a leading minus`() {
        assertThat(MoneyFormat.format(-500.5, "INR")).isEqualTo("-₹500.5")
    }

    @Test
    fun `signed rupee form uses explicit plus and minus signs`() {
        assertThat(MoneyFormat.signed(12000.0, positive = true, currencyCode = "INR")).isEqualTo("+₹12,000")
        assertThat(MoneyFormat.signed(12000.0, positive = false, currencyCode = "INR")).isEqualTo("−₹12,000")
        // The magnitude is always absolute; the sign comes from the flag.
        assertThat(MoneyFormat.signed(-450.0, positive = false, currencyCode = "INR")).isEqualTo("−₹450")
    }

    @Test
    fun `compact rupees under a thousand are whole rupees`() {
        assertThat(MoneyFormat.compact(0.0, "INR")).isEqualTo("₹0")
        assertThat(MoneyFormat.compact(450.0, "INR")).isEqualTo("₹450")
        assertThat(MoneyFormat.compact(999.0, "INR")).isEqualTo("₹999")
    }

    @Test
    fun `compact rupee thousands use k with at most one decimal`() {
        assertThat(MoneyFormat.compact(1_000.0, "INR")).isEqualTo("₹1k")
        assertThat(MoneyFormat.compact(1_234.0, "INR")).isEqualTo("₹1.2k")
        assertThat(MoneyFormat.compact(45_000.0, "INR")).isEqualTo("₹45k")
        assertThat(MoneyFormat.compact(99_949.0, "INR")).isEqualTo("₹99.9k")
    }

    @Test
    fun `lakh boundary promotes instead of showing 100k`() {
        assertThat(MoneyFormat.compact(99_950.0, "INR")).isEqualTo("₹1L")
        assertThat(MoneyFormat.compact(1_20_000.0, "INR")).isEqualTo("₹1.2L")
        assertThat(MoneyFormat.compact(45_00_000.0, "INR")).isEqualTo("₹45L")
    }

    @Test
    fun `crore boundary promotes instead of showing 100L`() {
        assertThat(MoneyFormat.compact(99_95_000.0, "INR")).isEqualTo("₹1Cr")
        assertThat(MoneyFormat.compact(1_00_00_000.0, "INR")).isEqualTo("₹1Cr")
        assertThat(MoneyFormat.compact(2_35_00_000.0, "INR")).isEqualTo("₹2.4Cr")
    }

    @Test
    fun `compact rounding is half up within a unit`() {
        assertThat(MoneyFormat.compact(1_250.0, "INR")).isEqualTo("₹1.3k")
        assertThat(MoneyFormat.compact(1_240.0, "INR")).isEqualTo("₹1.2k")
        assertThat(MoneyFormat.compact(999.4, "INR")).isEqualTo("₹999")
        assertThat(MoneyFormat.compact(999.5, "INR")).isEqualTo("₹1k")
    }

    @Test
    fun `compact negative rupees keep a leading minus`() {
        assertThat(MoneyFormat.compact(-1_500.0, "INR")).isEqualTo("-₹1.5k")
        assertThat(MoneyFormat.compact(-500.0, "INR")).isEqualTo("-₹500")
    }

    @Test
    fun `rupee mask is the historic placeholder`() {
        assertThat(MoneyFormat.mask("INR")).isEqualTo("₹\u00A0••••••")
    }

    // endregion

    // region other currencies - their own convention, never a rupee sign

    @Test
    fun `chilean pesos group with a period and have no minor unit`() {
        assertThat(MoneyFormat.format(1000.0, "CLP")).isEqualTo("$1.000")
        assertThat(MoneyFormat.format(1000500.0, "CLP")).isEqualTo("$1.000.500")
        assertThat(MoneyFormat.format(450.0, "CLP")).isEqualTo("$450")
        // A stray fraction can only be noise for a zero-minor-unit currency.
        assertThat(MoneyFormat.format(1000.4, "CLP")).isEqualTo("$1.000")
        assertThat(MoneyFormat.signed(1000.0, positive = false, currencyCode = "CLP")).isEqualTo("−$1.000")
    }

    @Test
    fun `us dollars carry the disambiguated prefix and two minor digits`() {
        assertThat(MoneyFormat.format(1000.5, "USD")).isEqualTo("US$1,000.50")
        assertThat(MoneyFormat.format(1000.0, "USD")).isEqualTo("US$1,000")
        assertThat(MoneyFormat.format(40.95, "USD")).isEqualTo("US$40.95")
    }

    @Test
    fun `euros use the continental convention`() {
        assertThat(MoneyFormat.format(1000.5, "EUR")).isEqualTo("€1.000,50")
        assertThat(MoneyFormat.format(1234567.0, "EUR")).isEqualTo("€1.234.567")
    }

    @Test
    fun `yen has no minor unit and western grouping`() {
        assertThat(MoneyFormat.format(1000.0, "JPY")).isEqualTo("¥1,000")
        assertThat(MoneyFormat.format(1234567.0, "JPY")).isEqualTo("¥1,234,567")
    }

    @Test
    fun `compact non-rupee amounts use k M B - never lakh or crore`() {
        assertThat(MoneyFormat.compact(450.0, "CLP")).isEqualTo("$450")
        assertThat(MoneyFormat.compact(45_000.0, "CLP")).isEqualTo("$45k")
        assertThat(MoneyFormat.compact(1_20_000.0, "CLP")).isEqualTo("$120k")
        assertThat(MoneyFormat.compact(2_35_00_000.0, "USD")).isEqualTo("US$23.5M")
        assertThat(MoneyFormat.compact(3_000_000_000.0, "USD")).isEqualTo("US$3B")
    }

    @Test
    fun `masks keep their own symbol`() {
        assertThat(MoneyFormat.mask("CLP")).isEqualTo("$\u00A0••••••")
        assertThat(MoneyFormat.mask("EUR")).isEqualTo("€\u00A0••••••")
    }

    @Test
    fun `an unknown code renders with the code itself - never a guessed symbol`() {
        assertThat(MoneyFormat.format(1000.5, "XYZ")).isEqualTo("XYZ\u00A01,000.50")
        assertThat(CurrencyCatalog.isKnown("XYZ")).isFalse()
        assertThat(CurrencyCatalog.of("clp").code).isEqualTo("CLP")
    }

    @Test
    fun `zero-minor-unit currencies are exactly the ones that cannot carry a fraction`() {
        for (code in listOf("CLP", "JPY", "KRW")) {
            assertThat(CurrencyCatalog.of(code).zeroMinorUnits).isTrue()
        }
        for (code in listOf("INR", "USD", "EUR")) {
            assertThat(CurrencyCatalog.of(code).zeroMinorUnits).isFalse()
        }
    }

    // endregion
}
