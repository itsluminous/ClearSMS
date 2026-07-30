package app.clearsms.data.rules

import app.clearsms.domain.parser.RuleGuards
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * The `guards_none` rule clause: a rule does not apply when any listed named
 * guard matches the body. Guard ids resolve against the guard library plus
 * the rule-guard extension document (`rules/rule_guards.json`); an unknown
 * id makes the rule skip-and-log (never fire without its veto, never crash),
 * and the extension document follows the guard library's identity and
 * load-safety discipline.
 */
class GuardsNoneTest {
    private fun rule(
        id: String = "r1",
        guardsNone: List<String>,
        bodyPattern: String = "(?i)debited",
    ) = RuleDefinition(
        id = id,
        priority = 10,
        match = RuleMatch(bodyPattern = bodyPattern, guardsNone = guardsNone),
        action = RuleAction(category = "important"),
    )

    // region evaluation semantics

    @Test
    fun `a rule is suppressed when a listed guard matches`() {
        val result =
            RuleEngine().evaluate(
                listOf(rule(guardsNone = listOf("otp_mention"))),
                "BANK",
                "OTP for txn: Rs.500 will be debited. Code 1234",
            )
        assertThat(result).isNull()
    }

    @Test
    fun `the same rule fires when no listed guard matches`() {
        val result =
            RuleEngine().evaluate(
                listOf(rule(guardsNone = listOf("otp_mention"))),
                "BANK",
                "Rs.500 debited from A/c XX1234",
            )
        assertThat(result).isNotNull()
    }

    @Test
    fun `guard library ids are referencable from rules directly`() {
        // settled_payment is a step-2 guard library guard, not an extension.
        val suppressed =
            RuleEngine().evaluate(
                listOf(rule(guardsNone = listOf("settled_payment"), bodyPattern = "(?i)payment")),
                "BANK",
                "We have received your payment of Rs.5,000",
            )
        assertThat(suppressed).isNull()
        val fires =
            RuleEngine().evaluate(
                listOf(rule(guardsNone = listOf("settled_payment"), bodyPattern = "(?i)payment")),
                "BANK",
                "Your payment of Rs.5,000 is pending",
            )
        assertThat(fires).isNotNull()
    }

    @Test
    fun `an unknown guard id skips the rule with a log, siblings still match`() {
        val logs = mutableListOf<String>()
        val engine = RuleEngine(log = logs::add)
        val healthy =
            RuleDefinition(
                id = "healthy",
                priority = 1,
                match = RuleMatch(bodyPattern = "(?i)debited"),
                action = RuleAction(category = "promotional"),
            )
        val result =
            engine.evaluate(
                listOf(rule(id = "broken", guardsNone = listOf("no_such_guard")), healthy),
                "BANK",
                "Rs.500 debited",
            )
        assertThat(result!!.matchedRuleId).isEqualTo("healthy")
        assertThat(logs.any { it.contains("unknown guard id 'no_such_guard'") }).isTrue()
    }

    @Test
    fun `guards_none round-trips through the entity mapping`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val definition = rule(guardsNone = listOf("otp_mention", "settled_payment"))
        val back = definition.toEntity(json, RuleSources.USER).toDefinition(json)
        assertThat(back!!.match.guardsNone).containsExactly("otp_mention", "settled_payment").inOrder()
    }

    // endregion

    // region rule-guard extension document

    @Test
    fun `rule guards master and bundled copy are identical`() {
        val master = repoFile("rules/rule_guards.json")
        val bundled = repoFile("app/src/main/assets/guards/rule_guards.json")
        assertThat(bundled.readText()).isEqualTo(master.readText())
    }

    @Test
    fun `otp_mention matches exactly like the contains clause it replaces`() {
        // body.contains("OTP", ignoreCase = true) semantics: bare substring,
        // any case, inside words too.
        assertThat(RuleGuards.matches("otp_mention", "Your OTP is 1234")).isTrue()
        assertThat(RuleGuards.matches("otp_mention", "do not share otp with anyone")).isTrue()
        assertThat(RuleGuards.matches("otp_mention", "use OTPs carefully")).isTrue()
        assertThat(RuleGuards.matches("otp_mention", "Rs.500 debited from A/c")).isFalse()
    }

    @Test
    fun `malformed extension document degrades to no extension guards`() {
        assertThat(RuleGuards.parse("{ not json")).isEmpty()
        assertThat(RuleGuards.parse(null)).isEmpty()
    }

    @Test
    fun `unsafe extension patterns are rejected at load`() {
        val parsed =
            RuleGuards.parse(
                """{"guards":[{"id":"bad","patterns":[".*boom","(a+)+b","(?i)fine"]}]}""",
            )
        assertThat(parsed.getValue("bad")).hasSize(1)
    }

    @Test
    fun `an extension id shadowing a guard library id is skipped`() {
        val parsed =
            RuleGuards.parse(
                """{"guards":[{"id":"settled_payment","patterns":["(?i)x"]}]}""",
            )
        assertThat(parsed).isEmpty()
        // Resolution still reaches the real guard library entry.
        assertThat(RuleGuards.isKnown("settled_payment")).isTrue()
    }

    @Test
    fun `unknown ids are not known and never match`() {
        assertThat(RuleGuards.isKnown("no_such_guard")).isFalse()
        assertThat(RuleGuards.matches("no_such_guard", "anything at all")).isFalse()
    }

    // endregion

    // region converted bundled rules behave identically

    @Test
    fun `converted debit rule still fires on a debit and not on an OTP quote`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val document =
            json.decodeFromString(
                RuleDocument.serializer(),
                repoFile("app/src/main/assets/default_rules.json").readText(),
            )
        val engine = RuleEngine()
        // hdfc-debit-01 was converted from body_must_not_contain ["OTP","otp"].
        val debit =
            engine.evaluate(
                document.rules,
                "VM-HDFCBK",
                "Rs.500.00 debited from HDFC Bank A/c XX1234 on 12-07-26 for UPI",
            )
        assertThat(debit).isNotNull()
        assertThat(debit!!.matchedRuleId).doesNotContain("otp")
        // The same shape quoting an OTP must not match any converted debit
        // rule — the generic OTP rule takes it instead.
        val otp =
            engine.evaluate(
                document.rules,
                "VM-HDFCBK",
                "OTP 123456: Rs.500.00 will be debited from HDFC Bank A/c XX1234 if you confirm",
            )
        if (otp != null) {
            assertThat(otp.category).isEqualTo(app.clearsms.domain.model.Category.OTP)
        }
    }

    @Test
    fun `no bundled rule references an unknown guard id`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val document =
            json.decodeFromString(
                RuleDocument.serializer(),
                repoFile("app/src/main/assets/default_rules.json").readText(),
            )
        val unknown =
            document.rules
                .flatMap { it.match.guardsNone }
                .distinct()
                .filterNot { RuleGuards.isKnown(it) }
        assertThat(unknown).isEmpty()
    }

    // endregion

    private fun repoFile(repoRelativePath: String): File =
        sequenceOf(
            File(repoRelativePath),
            File("..", repoRelativePath),
            File(repoRelativePath.removePrefix("app/")),
        ).first(File::exists)
}
