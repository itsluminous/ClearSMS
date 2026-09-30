package app.clearsms.domain.parser

import app.clearsms.domain.model.CurrencyCatalog
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The separator rule (see [AmountParser]) - the fix for issue #65, where a
 * Chilean `1.000` was read as 1.0 and every total was off by 1000x.
 */
class AmountParserTest {
    private val inr = CurrencyCatalog.of("INR")
    private val clp = CurrencyCatalog.of("CLP")
    private val usd = CurrencyCatalog.of("USD")
    private val eur = CurrencyCatalog.of("EUR")
    private val jpy = CurrencyCatalog.of("JPY")

    @Test
    fun `rule 1 - a zero-minor-unit currency never has a fraction`() {
        assertThat(AmountParser.parse("1.000", clp)).isEqualTo(1000.0)
        assertThat(AmountParser.parse("1.000.500", clp)).isEqualTo(1_000_500.0)
        assertThat(AmountParser.parse("1,000", clp)).isEqualTo(1000.0)
        assertThat(AmountParser.parse("1000", jpy)).isEqualTo(1000.0)
        assertThat(AmountParser.parse("12.345", jpy)).isEqualTo(12345.0)
        // Both separators in a CLP body: the last is a stray decimal mark,
        // its fraction is dropped - 1000, never 100050.
        assertThat(AmountParser.parse("1.000,50", clp)).isEqualTo(1000.0)
    }

    @Test
    fun `rule 2 - with both separators the last one is the decimal mark`() {
        assertThat(AmountParser.parse("1,000.50", inr)).isEqualTo(1000.5)
        assertThat(AmountParser.parse("1,000.50", usd)).isEqualTo(1000.5)
        assertThat(AmountParser.parse("1.000,50", eur)).isEqualTo(1000.5)
        // Self-describing: the currency's own convention does not override it.
        assertThat(AmountParser.parse("1.000,50", usd)).isEqualTo(1000.5)
        assertThat(AmountParser.parse("1,000.50", eur)).isEqualTo(1000.5)
        assertThat(AmountParser.parse("1,23,456.78", inr)).isEqualTo(123456.78)
    }

    @Test
    fun `rule 3 - a repeated separator groups`() {
        assertThat(AmountParser.parse("1,00,000", inr)).isEqualTo(100000.0)
        assertThat(AmountParser.parse("1.000.500", eur)).isEqualTo(1_000_500.0)
        assertThat(AmountParser.parse("1.000.500", usd)).isEqualTo(1_000_500.0)
    }

    @Test
    fun `rule 4 - exactly three digits after the only separator is a thousands group`() {
        // The documented resolution of the genuinely ambiguous `1.000`: a
        // written fraction has one or two digits, a thousands group three.
        assertThat(AmountParser.parse("1.000", usd)).isEqualTo(1000.0)
        assertThat(AmountParser.parse("1.000", eur)).isEqualTo(1000.0)
        assertThat(AmountParser.parse("1,000", eur)).isEqualTo(1000.0)
        assertThat(AmountParser.parse("12.500", inr)).isEqualTo(12500.0)
        // ...unless the currency genuinely has three minor digits.
        assertThat(AmountParser.parse("12.500", CurrencyCatalog.of("KWD"))).isEqualTo(12.5)
    }

    @Test
    fun `rule 5 - otherwise the currency's convention decides`() {
        assertThat(AmountParser.parse("1.50", inr)).isEqualTo(1.5)
        assertThat(AmountParser.parse("1.5", inr)).isEqualTo(1.5)
        assertThat(AmountParser.parse("1,50", eur)).isEqualTo(1.5)
        assertThat(AmountParser.parse("1.50", eur)).isEqualTo(150.0)
        assertThat(AmountParser.parse("1,5", inr)).isEqualTo(15.0)
        assertThat(AmountParser.parse("286368.5", inr)).isEqualTo(286368.5)
    }

    @Test
    fun `every rupee figure the app ever parsed reads exactly as before`() {
        val legacy: (String) -> Double? = { it.replace(",", "").toDoubleOrNull() }
        for (raw in listOf("1,299.00", "13,000.00", "1,07,721.74", "286368.5", "40,194.56", "500", "55.00", "1,000", "0.50", "14807")) {
            assertThat(AmountParser.parse(raw, inr)).isEqualTo(legacy(raw))
        }
    }

    @Test
    fun `garbage is rejected rather than guessed`() {
        assertThat(AmountParser.parse("", inr)).isNull()
        assertThat(AmountParser.parse(".50", inr)).isNull()
        // A swallowed trailing separator is punctuation, not a decimal mark.
        assertThat(AmountParser.parse("1,000.", inr)).isEqualTo(1000.0)
        assertThat(AmountParser.parse("500,", inr)).isEqualTo(500.0)
        assertThat(AmountParser.parse("1.2.3.4", inr)).isEqualTo(1234.0)
        assertThat(AmountParser.parse("1.50.25", inr)).isEqualTo(15025.0)
        assertThat(AmountParser.parse("abc", inr)).isNull()
        assertThat(AmountParser.parse("1,000.50.5", inr)).isNull()
    }
}
