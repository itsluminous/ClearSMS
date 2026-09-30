package app.clearsms.data.rules

import app.clearsms.domain.model.amount
import app.clearsms.domain.parser.CurrencyContext
import app.clearsms.domain.parser.TransactionParser
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Rule `amount` extracts are typed under the BODY's currency (issue #65): a
 * user rule capturing `1.000` from a Chilean peso SMS yields 1000, while
 * every rupee capture reads exactly as the engine always read it.
 */
class RuleEngineCurrencyTest {
    private fun rule(bodyPattern: String) =
        RuleDefinition(
            id = "user:clp-1",
            priority = 10,
            match = RuleMatch(bodyPattern = bodyPattern),
            action = RuleAction(category = "important", extract = mapOf("amount" to "$1", "type" to "debit")),
        )

    @Test
    fun `a chilean capture reads a thousand pesos under the body's currency`() {
        val chile = TransactionParser { CurrencyContext(deviceCurrency = "CLP") }
        val engine = RuleEngine(currencyOf = chile::currencyOf)
        val result = engine.evaluate(listOf(rule("(?i)compra por \\$([\\d.,]+)")), "BANCO", "Compra por $1.000 en LIDER")
        assertThat(result?.typed?.amount("amount")).isEqualTo(1000.0)
        val million = engine.evaluate(listOf(rule("(?i)monto ([\\d.,]+)")), "BANCO", "Monto 1.000.500 abonado")
        assertThat(million?.typed?.amount("amount")).isEqualTo(1_000_500.0)
    }

    @Test
    fun `a rupee capture reads exactly as before with the default wiring`() {
        val engine = RuleEngine()
        val result = engine.evaluate(listOf(rule("(?i)Rs\\.?\\s*([\\d,]+(?:\\.\\d{1,2})?)")), "HDFCBK", "Rs.1,07,721.74 debited")
        assertThat(result?.typed?.amount("amount")).isEqualTo(107721.74)
        val body = engine.evaluate(listOf(rule("(?i)INR ([\\d,.]+)")), "HDFCBK", "INR 13,000.00 debited")
        assertThat(body?.typed?.amount("amount")).isEqualTo(13000.0)
    }

    @Test
    fun `the message's own currency wins over the device for rule captures too`() {
        val chile = TransactionParser { CurrencyContext(deviceCurrency = "CLP") }
        val engine = RuleEngine(currencyOf = chile::currencyOf)
        // A rupee SMS on a Chilean device: "1,000.50" is rupees and reads as 1000.5.
        val result = engine.evaluate(listOf(rule("(?i)Rs\\.?\\s*([\\d,.]+)")), "HDFCBK", "Rs.1,000.50 debited from A/c XX1234")
        assertThat(result?.typed?.amount("amount")).isEqualTo(1000.5)
    }
}
