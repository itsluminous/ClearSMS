package app.clearsms.domain.rules

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RuleComposerTest {
    private val debitSms =
        "Rs.2,500.00 debited from a/c XX3456 on 12-07-25 to VPA merchant@okicici. " +
            "UPI Ref 519876543210. Avl Bal Rs.10,250.50"

    private fun picksFor(vararg kinds: TokenKind): List<CapturePick> {
        val tokens = RuleSuggester.suggest(debitSms)
        return kinds.map { kind ->
            val token = tokens.first { it.kind == kind }
            CapturePick(token, token.suggestedField ?: "value")
        }
    }

    @Test
    fun `composed pattern compiles and matches the source message`() {
        val composed = RuleComposer.composeBody(debitSms, picksFor(TokenKind.AMOUNT, TokenKind.ACCOUNT_LAST4))
        val match = Regex(composed.bodyPattern).find(debitSms)
        assertThat(match).isNotNull()
        assertThat(match!!.groupValues[1]).isEqualTo("2,500.00")
        assertThat(match.groupValues[2]).isEqualTo("3456")
    }

    @Test
    fun `extract mapping follows capture group order even when picks are given out of order`() {
        // Balance appears LAST in the message but is passed FIRST here.
        val composed = RuleComposer.composeBody(debitSms, picksFor(TokenKind.BALANCE, TokenKind.AMOUNT))
        assertThat(composed.extract).containsExactly("amount", "$1", "balance", "$2").inOrder()
        val match = Regex(composed.bodyPattern).find(debitSms)!!
        assertThat(match.groupValues[1]).isEqualTo("2,500.00")
        assertThat(match.groupValues[2]).isEqualTo("10,250.50")
    }

    @Test
    fun `capture group count always matches the extract mapping`() {
        val composed =
            RuleComposer.composeBody(debitSms, picksFor(TokenKind.AMOUNT, TokenKind.ACCOUNT_LAST4, TokenKind.BALANCE))
        assertThat(RuleComposer.captureGroupCount(composed.bodyPattern)).isEqualTo(composed.extract.size)
        assertThat(RuleComposer.maxGroupReference(composed.extract)).isEqualTo(composed.extract.size)
    }

    @Test
    fun `composed pattern matches sibling messages with different values`() {
        val composed = RuleComposer.composeBody(debitSms, picksFor(TokenKind.AMOUNT, TokenKind.ACCOUNT_LAST4))
        val sibling =
            "Rs.99.00 debited from a/c XX9999 on 01-01-26 to VPA merchant@okicici. " +
                "UPI Ref 500000000001. Avl Bal Rs.1,000.00"
        val match = Regex(composed.bodyPattern).find(sibling)
        assertThat(match).isNotNull()
        assertThat(match!!.groupValues[1]).isEqualTo("99.00")
        assertThat(match.groupValues[2]).isEqualTo("9999")
    }

    @Test
    fun `near-miss message does not match`() {
        val composed = RuleComposer.composeBody(debitSms, picksFor(TokenKind.AMOUNT))
        val credited = debitSms.replace("debited", "credited")
        assertThat(Regex(composed.bodyPattern).find(credited)).isNull()
    }

    @Test
    fun `no catch-all wrapper is ever emitted`() {
        val composedWithEdgeTokens =
            RuleComposer.composeBody(debitSms, picksFor(TokenKind.AMOUNT, TokenKind.BALANCE))
        for (pattern in listOf(composedWithEdgeTokens.bodyPattern, RuleComposer.composeBody(debitSms, emptyList()).bodyPattern)) {
            assertThat(RuleComposer.hasCatchAllWrapper(pattern)).isFalse()
            assertThat(pattern.removePrefix("(?i)").startsWith(".*")).isFalse()
            assertThat(pattern.endsWith(".*")).isFalse()
        }
    }

    @Test
    fun `catch-all wrapper detector flags dangerous edges`() {
        assertThat(RuleComposer.hasCatchAllWrapper(".*debited.*")).isTrue()
        assertThat(RuleComposer.hasCatchAllWrapper("(?i).*debited")).isTrue()
        assertThat(RuleComposer.hasCatchAllWrapper("""[\s\S]*OTP""")).isTrue()
        assertThat(RuleComposer.hasCatchAllWrapper("""debited[\s\S]+""")).isTrue()
        assertThat(RuleComposer.hasCatchAllWrapper("""visit a\.*""")).isFalse()
        assertThat(RuleComposer.hasCatchAllWrapper("""(?i)Rs\.?\s*([\d,]+) debited""")).isFalse()
    }

    @Test
    fun `regex metacharacters in literal context are escaped`() {
        val body = "Win $500 (T&C apply)! Visit a.b + more?"
        val composed = RuleComposer.composeBody(body, emptyList())
        val regex = Regex(composed.bodyPattern)
        assertThat(regex.find(body)).isNotNull()
        assertThat(regex.find("Win 500 TC apply Visit aXb more")).isNull()
    }

    @Test
    fun `unpicked digit groups are generalized to bounded ranges`() {
        val composed = RuleComposer.composeBody(debitSms, picksFor(TokenKind.AMOUNT))
        // The unpicked date must not remain a rigid literal.
        assertThat(composed.bodyPattern).doesNotContain("12-07-25")
        assertThat(composed.bodyPattern).contains("""\d{1,4}\-\d{1,4}\-\d{1,4}""")
        // And no unbounded catch-alls appear anywhere.
        assertThat(composed.bodyPattern).doesNotContain(".*")
        assertThat(composed.bodyPattern).doesNotContain("""[\s\S]""")
    }

    @Test
    fun `otp pick captures the code in sibling messages`() {
        val source = "483920 is your OTP for netbanking login. Valid for 10 mins."
        val otp = RuleSuggester.suggest(source).first { it.kind == TokenKind.OTP_CODE }
        val composed = RuleComposer.composeBody(source, listOf(CapturePick(otp, RuleSuggester.Fields.OTP_CODE)))
        val sibling = "777123 is your OTP for netbanking login. Valid for 10 mins."
        val match = Regex(composed.bodyPattern).find(sibling)
        assertThat(match).isNotNull()
        assertThat(match!!.groupValues[1]).isEqualTo("777123")
        assertThat(composed.extract).containsExactly(RuleSuggester.Fields.OTP_CODE, "$1")
    }

    @Test
    fun `whitespace runs are generalized`() {
        val composed = RuleComposer.composeBody("Hello   world", emptyList())
        assertThat(composed.bodyPattern).isEqualTo("""(?i)Hello\s+world""")
        assertThat(Regex(composed.bodyPattern).containsMatchIn("hello world")).isTrue()
    }

    @Test
    fun `vendor capture generalizes to sibling merchants`() {
        val source = "Paid Rs.120.00 to alice@oksbi via UPI"
        val tokens = RuleSuggester.suggest(source)
        val vendor = tokens.filter { it.kind == TokenKind.VENDOR }.minBy { it.rank }
        val amount = tokens.first { it.kind == TokenKind.AMOUNT }
        val composed =
            RuleComposer.composeBody(
                source,
                listOf(
                    CapturePick(amount, RuleSuggester.Fields.AMOUNT),
                    CapturePick(vendor, RuleSuggester.Fields.MERCHANT),
                ),
            )
        val match = Regex(composed.bodyPattern).find("Paid Rs.75.50 to bob-store@okhdfc via UPI")
        assertThat(match).isNotNull()
        assertThat(match!!.groupValues[1]).isEqualTo("75.50")
        assertThat(match.groupValues[2]).isEqualTo("bob-store@okhdfc")
    }
}
