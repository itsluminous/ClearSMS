package app.clearsms.data.rules

import app.clearsms.domain.model.ExtractedValue
import app.clearsms.domain.model.TransactionType
import app.clearsms.domain.model.amount
import app.clearsms.domain.model.date
import app.clearsms.domain.model.merchant
import app.clearsms.domain.model.transactionType
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.time.LocalDate

/**
 * Typed rule extracts: the engine resolves each capture ONCE to a typed
 * value using the shared parsing algorithms — the type inferred from the
 * well-known extract key, or declared via `extract_types` where inference
 * would be wrong. Raw capture text is always preserved unchanged; a value
 * that fails to parse as its type degrades to raw-only; a rule declaring an
 * unknown type name is skipped without crashing the engine.
 */
class TypedExtractsTest {
    private val engine = RuleEngine()

    private fun rule(
        bodyPattern: String,
        extract: Map<String, String>,
        extractTypes: Map<String, String> = emptyMap(),
    ) = RuleDefinition(
        id = "t1",
        priority = 10,
        match = RuleMatch(bodyPattern = bodyPattern),
        action = RuleAction(category = "important", extract = extract, extractTypes = extractTypes),
    )

    // region each type parses correctly

    @Test
    fun `amount extract parses comma-grouped digits once`() {
        val result =
            engine.evaluate(
                listOf(rule("""Rs\.([\d,]+\.\d{2}) debited""", mapOf("amount" to "$1"))),
                "S",
                "Rs.1,23,456.78 debited from A/c",
            )
        assertThat(result!!.typed.amount("amount")).isEqualTo(123456.78)
        assertThat(result.extracted["amount"]).isEqualTo("1,23,456.78")
    }

    @Test
    fun `date extract parses through the shared date grammar`() {
        val result =
            engine.evaluate(
                listOf(rule("""due on (\d{2}-\d{2}-\d{2})""", mapOf("due_date" to "$1"))),
                "S",
                "Your bill is due on 05-08-26",
            )
        assertThat(result!!.typed.date("due_date")).isEqualTo(LocalDate.of(2026, 8, 5))
        assertThat(result.extracted["due_date"]).isEqualTo("05-08-26")
    }

    @Test
    fun `merchant extract is normalized once and keeps its raw capture`() {
        val result =
            engine.evaluate(
                listOf(rule("""Info: (.+)""", mapOf("merchant" to "$1"))),
                "S",
                "Info: XXXXXXXXXX6894- RD Installment-Jul 2026",
            )
        assertThat(result!!.typed.merchant("merchant")).isEqualTo("RD Installment")
        assertThat(result.extracted["merchant"]).isEqualTo("XXXXXXXXXX6894- RD Installment-Jul 2026")
    }

    @Test
    fun `merchant capture that is pure reference noise normalizes to null`() {
        val result =
            engine.evaluate(
                listOf(rule("""Info: (.+)""", mapOf("merchant" to "$1"))),
                "S",
                "Info: 1234567890123",
            )
        val value = result!!.typed["merchant"] as ExtractedValue.Merchant
        assertThat(value.normalized).isNull()
        assertThat(value.raw).isEqualTo("1234567890123")
    }

    @Test
    fun `transaction type extract maps debit and credit`() {
        val debit =
            engine.evaluate(
                listOf(rule("debited", mapOf("type" to "debit"))),
                "S",
                "Rs 10 debited",
            )
        assertThat(debit!!.typed.transactionType("type")).isEqualTo(TransactionType.DEBIT)
        val credit =
            engine.evaluate(
                listOf(rule("credited", mapOf("type" to "credit"))),
                "S",
                "Rs 10 credited",
            )
        assertThat(credit!!.typed.transactionType("type")).isEqualTo(TransactionType.CREDIT)
    }

    @Test
    fun `unlisted keys default to text`() {
        val result =
            engine.evaluate(
                listOf(rule("""Ref (\w+)""", mapOf("reference" to "$1", "bank" to "HDFC Bank"))),
                "S",
                "Ref AB123",
            )
        assertThat(result!!.typed["reference"]).isEqualTo(ExtractedValue.Text("AB123"))
        assertThat(result.typed["bank"]).isEqualTo(ExtractedValue.Text("HDFC Bank"))
    }

    // endregion

    // region inference and overrides

    @Test
    fun `well-known amount-like keys infer the amount type`() {
        val result =
            engine.evaluate(
                listOf(
                    rule(
                        """Bal ([\d,]+\.\d{2}) total ([\d,]+) min ([\d,]+)""",
                        mapOf("balance" to "$1", "total_due" to "$2", "min_due" to "$3"),
                    ),
                ),
                "S",
                "Bal 5,000.25 total 1,200 min 100",
            )
        assertThat(result!!.typed.amount("balance")).isEqualTo(5000.25)
        assertThat(result.typed.amount("total_due")).isEqualTo(1200.0)
        assertThat(result.typed.amount("min_due")).isEqualTo(100.0)
    }

    @Test
    fun `explicit extract_types overrides the inferred type`() {
        // "amount" would infer AMOUNT; the rule declares it text (say the
        // capture is a masked figure the rule wants verbatim).
        val result =
            engine.evaluate(
                listOf(
                    rule(
                        """worth (\S+)""",
                        mapOf("amount" to "$1"),
                        extractTypes = mapOf("amount" to "text"),
                    ),
                ),
                "S",
                "worth 1,500",
            )
        assertThat(result!!.typed["amount"]).isEqualTo(ExtractedValue.Text("1,500"))
        assertThat(result.typed.amount("amount")).isNull()
    }

    @Test
    fun `explicit extract_types can type an unknown key`() {
        val result =
            engine.evaluate(
                listOf(
                    rule(
                        """renews on (\d{2}-\d{2}-\d{4})""",
                        mapOf("renewal_date" to "$1"),
                        extractTypes = mapOf("renewal_date" to "date"),
                    ),
                ),
                "S",
                "Your plan renews on 15-09-2026",
            )
        assertThat(result!!.typed.date("renewal_date")).isEqualTo(LocalDate.of(2026, 9, 15))
    }

    // endregion

    // region degradation

    @Test
    fun `a value that fails to parse as its type keeps raw and drops typed`() {
        val logs = mutableListOf<String>()
        val engine = RuleEngine(log = logs::add)
        val result =
            engine.evaluate(
                listOf(rule("""got (\w+)""", mapOf("amount" to "$1"))),
                "S",
                "got nothing",
            )
        assertThat(result!!.extracted["amount"]).isEqualTo("nothing")
        assertThat(result.typed).doesNotContainKey("amount")
        assertThat(logs.single()).contains("does not parse as amount")
    }

    @Test
    fun `a rule declaring an unknown extract type is skipped and logged, not fatal`() {
        val logs = mutableListOf<String>()
        val engine = RuleEngine(log = logs::add)
        val malformed =
            rule("hello", mapOf("amount" to "$1"), extractTypes = mapOf("amount" to "no_such_type"))
        val healthy =
            RuleDefinition(
                id = "healthy",
                priority = 1,
                match = RuleMatch(bodyPattern = "hello"),
                action = RuleAction(category = "promotional"),
            )
        val result = engine.evaluate(listOf(malformed, healthy), "S", "hello")
        // The malformed rule never matches; its healthy sibling still does.
        assertThat(result!!.matchedRuleId).isEqualTo("healthy")
        assertThat(logs.any { it.contains("unknown extract type 'no_such_type'") }).isTrue()
    }

    @Test
    fun `an unparseable transaction type drops the typed value only`() {
        val result =
            engine.evaluate(
                listOf(rule("""was (\w+)""", mapOf("type" to "$1"))),
                "S",
                "it was reversed",
            )
        assertThat(result!!.extracted["type"]).isEqualTo("reversed")
        assertThat(result.typed.transactionType("type")).isNull()
    }

    // endregion

    // region schema round-trip

    @Test
    fun `extract_types round-trips through the entity mapping`() {
        val json = Json { ignoreUnknownKeys = true }
        val definition =
            rule("x", mapOf("k" to "$1"), extractTypes = mapOf("k" to "amount"))
        val back = definition.toEntity(json, RuleSources.USER).toDefinition(json)
        assertThat(back!!.action.extractTypes).containsExactly("k", "amount")
    }

    @Test
    fun `terse rules without extract_types serialize without the field`() {
        val json = Json { ignoreUnknownKeys = true }
        val encoded =
            json.encodeToString(
                RuleAction.serializer(),
                RuleAction(category = "important", extract = mapOf("amount" to "$1")),
            )
        assertThat(encoded).doesNotContain("extract_types")
    }

    // endregion
}
