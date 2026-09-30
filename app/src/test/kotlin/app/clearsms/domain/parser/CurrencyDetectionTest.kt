package app.clearsms.domain.parser

import app.clearsms.domain.model.TransactionType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Currency detection precedence (message > override > SIM/locale) and the
 * end-to-end parse of non-rupee bank SMS - the correctness fix of issue #65.
 * Every body is synthetic.
 */
class CurrencyDetectionTest {
    private val chile = CurrencyContext(deviceCurrency = "CLP")
    private val india = CurrencyContext.INDIA

    // region detector

    @Test
    fun `an unambiguous marker in the message beats the device locale`() {
        assertThat(CurrencyDetector.detect("Spent USD 40.95 on your card", chile)).isEqualTo("USD")
        assertThat(CurrencyDetector.detect("Rs.250.00 debited from A/c XX9805", chile)).isEqualTo("INR")
        assertThat(CurrencyDetector.detect("INR 13,000.00 debited", chile)).isEqualTo("INR")
        assertThat(CurrencyDetector.detect("\u20b9500 debited", chile)).isEqualTo("INR")
        assertThat(CurrencyDetector.detect("Pago de \u20ac1.000,50 aprobado", chile)).isEqualTo("EUR")
        assertThat(CurrencyDetector.detect("Compra por CLP 1.000 en TIENDA", india)).isEqualTo("CLP")
        assertThat(CurrencyDetector.detect("Compra por 1.000 CLP en TIENDA", india)).isEqualTo("CLP")
    }

    @Test
    fun `an unambiguous marker in the message beats the settings override too`() {
        val forcedClp = CurrencyContext(deviceCurrency = "INR", override = "CLP")
        // The bank knows what it charged: an Indian user's USD spend stays USD.
        assertThat(CurrencyDetector.detect("Spent USD 40.95 on your card", forcedClp)).isEqualTo("USD")
        assertThat(CurrencyDetector.detect("\u20b9500 debited", forcedClp)).isEqualTo("INR")
    }

    @Test
    fun `a bare dollar sign is resolved by the fallback within the dollar family`() {
        assertThat(CurrencyDetector.detect("Compra por $1.000 en TIENDA", chile)).isEqualTo("CLP")
        assertThat(CurrencyDetector.detect("Paid $40.95 at STORE", CurrencyContext(deviceCurrency = "USD"))).isEqualTo("USD")
        assertThat(CurrencyDetector.detect("Paid $40.95 at STORE", CurrencyContext(deviceCurrency = "AUD"))).isEqualTo("AUD")
        // A non-dollar home (India, the eurozone) cannot claim a `$`: USD.
        assertThat(CurrencyDetector.detect("Paid $40.95 at STORE", india)).isEqualTo("USD")
        assertThat(CurrencyDetector.detect("Paid $40.95 at STORE", CurrencyContext(deviceCurrency = "EUR"))).isEqualTo("USD")
        // A disambiguated sign needs no fallback.
        assertThat(CurrencyDetector.detect("Compra por US$40 en TIENDA", chile)).isEqualTo("USD")
        assertThat(CurrencyDetector.detect("Compra por CLP$1.000", india)).isEqualTo("CLP")
    }

    @Test
    fun `the settings override decides every ambiguous marker and every bare number`() {
        val forcedClp = CurrencyContext(deviceCurrency = "USD", override = "CLP")
        assertThat(CurrencyDetector.detect("Compra por $1.000 en TIENDA", forcedClp)).isEqualTo("CLP")
        assertThat(CurrencyDetector.detect("Monto 1.000 aprobado", forcedClp)).isEqualTo("CLP")
        // Rs is a family too (Indian, Sri Lankan, Nepali, Pakistani rupees).
        val srilanka = CurrencyContext(deviceCurrency = "LKR")
        assertThat(CurrencyDetector.detect("Rs.250 debited", srilanka)).isEqualTo("LKR")
        assertThat(CurrencyDetector.detect("Rs.250 debited", india)).isEqualTo("INR")
        assertThat(CurrencyDetector.detect("Rs.250 debited", chile)).isEqualTo("INR")
    }

    @Test
    fun `no marker at all falls back to the device or override`() {
        assertThat(CurrencyDetector.detect("Monto 1.000 aprobado", chile)).isEqualTo("CLP")
        assertThat(CurrencyDetector.detect("Amount 1,000 approved", india)).isEqualTo("INR")
    }

    @Test
    fun `iso codes need a number beside them so words are never currencies`() {
        // "TRY" is Turkish lira and an English word; "PEN" a Peruvian sol and a pen.
        assertThat(CurrencyDetector.detect("PLEASE TRY AGAIN LATER. Rs.500 debited", india)).isEqualTo("INR")
        assertThat(CurrencyDetector.detect("USE A PEN. Rs.500 debited", india)).isEqualTo("INR")
        assertThat(CurrencyDetector.detect("Ref UPI 123456789012 Rs.500 debited", india)).isEqualTo("INR")
        // Lower-case text is never a code either.
        assertThat(CurrencyDetector.detect("usd 40 debited", india)).isEqualTo("INR")
    }

    @Test
    fun `the first marker in reading order wins - the transaction, not the trailing state`() {
        val body = "Spent USD 40.95 on Axis Bank Card no. XX5106 at UBER. Avl Limit: INR 286368.5"
        assertThat(CurrencyDetector.detect(body, india)).isEqualTo("USD")
    }

    // endregion

    // region end-to-end parsing

    private val chileParser = TransactionParser { chile }
    private val indiaParser = TransactionParser()

    @Test
    fun `chilean 1_000 is one thousand pesos - not one peso`() {
        val tx = chileParser.parse("BANCO", "Compra por $1.000 en LIDER con tarjeta terminada en 1234 debited. Ref 998877")
        assertThat(tx).isNotNull()
        assertThat(tx!!.amount).isEqualTo(1000.0)
        assertThat(tx.currency).isEqualTo("CLP")
        assertThat(tx.type).isEqualTo(TransactionType.DEBIT)
    }

    @Test
    fun `chilean 1_000_500 is a million and change with no fraction`() {
        val tx = chileParser.parse("BANCO", "Transferencia de $1.000.500 received en cuenta terminada en 4321")
        assertThat(tx!!.amount).isEqualTo(1_000_500.0)
        assertThat(tx.currency).isEqualTo("CLP")
        // No fractional part can exist: a stray decimal reading is impossible.
        assertThat(tx.amount % 1.0).isEqualTo(0.0)
    }

    @Test
    fun `an INR amount parses exactly as before`() {
        val tx = indiaParser.parse("HDFCBK", "Rs.1,000.50 debited from A/c XX9805 to VPA merchant@okicici on 20-07-26.")
        assertThat(tx!!.amount).isEqualTo(1000.5)
        assertThat(tx.currency).isEqualTo("INR")
        // A rupee message on a Chilean device is still rupees: the message wins.
        val onChileDevice = chileParser.parse("HDFCBK", "Rs.1,000.50 debited from A/c XX9805 to VPA merchant@okicici on 20-07-26.")
        assertThat(onChileDevice!!.amount).isEqualTo(1000.5)
        assertThat(onChileDevice.currency).isEqualTo("INR")
    }

    @Test
    fun `a US dollar amount keeps its cents`() {
        val tx =
            TransactionParser {
                CurrencyContext(
                    deviceCurrency = "USD",
                )
            }.parse("CHASE", "You paid $1,000.50 at STORE with card ending 1234")
        assertThat(tx!!.amount).isEqualTo(1000.5)
        assertThat(tx.currency).isEqualTo("USD")
    }

    @Test
    fun `a euro amount reads the continental convention`() {
        val tx = indiaParser.parse("BANK", "Payment of EUR 1.000,50 debited from card ending 1234")
        assertThat(tx!!.amount).isEqualTo(1000.5)
        assertThat(tx.currency).isEqualTo("EUR")
        val symbol = chileParser.parse("BANK", "Payment of \u20ac1.000,50 debited from card ending 1234")
        assertThat(symbol!!.amount).isEqualTo(1000.5)
        assertThat(symbol.currency).isEqualTo("EUR")
    }

    @Test
    fun `yen has no minor unit`() {
        val tx = indiaParser.parse("BANK", "Spent JPY 1000 on your card ending 1234 at TOKYO STORE")
        assertThat(tx!!.amount).isEqualTo(1000.0)
        assertThat(tx.currency).isEqualTo("JPY")
        val grouped = indiaParser.parse("BANK", "Paid \u00a512,345 at TOKYO STORE with card ending 1234")
        assertThat(grouped!!.amount).isEqualTo(12345.0)
        assertThat(grouped.currency).isEqualTo("JPY")
    }

    @Test
    fun `the ambiguous 1_000 resolves to one thousand by the documented rule`() {
        // USD writes `.` as its decimal mark, yet a lone three-digit tail is
        // a thousands group (rule 4) - so even a Chilean message read on a
        // wrongly-configured US device comes out right.
        val tx = TransactionParser { CurrencyContext(deviceCurrency = "USD") }.parse("BANCO", "Compra por $1.000 en LIDER debited")
        assertThat(tx!!.amount).isEqualTo(1000.0)
        assertThat(tx.currency).isEqualTo("USD")
    }

    @Test
    fun `a message naming a currency that disagrees with the device wins`() {
        // Device says Chile; the bank says US dollars. The bank wins.
        val tx = chileParser.parse("AXISBK", "Spent USD 40.95 on Axis Bank Card no. XX5106 at UBER. Avl Limit: INR 286368.5")
        assertThat(tx!!.amount).isEqualTo(40.95)
        assertThat(tx.currency).isEqualTo("USD")
        assertThat(tx.availableLimit).isEqualTo(286368.5)
    }

    @Test
    fun `the settings override forces the currency of ambiguous amounts`() {
        // A Chilean on a device whose locale says the US, with CLP forced.
        val forced = TransactionParser { CurrencyContext(deviceCurrency = "USD", override = "CLP") }
        val tx = forced.parse("BANCO", "Compra por $1.500 en LIDER debited")
        assertThat(tx!!.amount).isEqualTo(1500.0)
        assertThat(tx.currency).isEqualTo("CLP")
        // ...but never an explicitly named one.
        val usd = forced.parse("BANCO", "Compra por USD 15.50 en AMAZON debited")
        assertThat(usd!!.amount).isEqualTo(15.5)
        assertThat(usd.currency).isEqualTo("USD")
    }

    @Test
    fun `the amount's own marker decides its currency - not an earlier mention`() {
        // An issuer quoting the foreign figure before the rupee debit: the
        // rupee-marked amount wins tier one and is stored as rupees.
        val tx = chileParser.parse("HDFCBK", "Your card was charged USD 10 = Rs.830.00 debited from A/c XX1234 on 20-07-26")
        assertThat(tx!!.amount).isEqualTo(830.0)
        assertThat(tx.currency).isEqualTo("INR")
        // Lower-case "inr" is still rupees, on any device.
        val lower = chileParser.parse("HDFCBK", "inr 500 debited from A/c XX1234")
        assertThat(lower!!.currency).isEqualTo("INR")
    }

    @Test
    fun `a bare number is never an amount - references, tails and dates stay out`() {
        // No currency marker anywhere: no transaction, whatever the locale.
        assertThat(chileParser.parse("BANK", "Payment debited. Ref 123456789012 on 12/07/26 card ending 1234")).isNull()
        assertThat(indiaParser.parse("BANK", "Payment debited. Ref 123456789012 on 12/07/26 card ending 1234")).isNull()
        // A dollar amount does not swallow a following reference or tail.
        val tx = chileParser.parse("BANCO", "Compra por $2.500 debited. Ref 123456789012 tarjeta 1234")
        assertThat(tx!!.amount).isEqualTo(2500.0)
    }

    // endregion
}
