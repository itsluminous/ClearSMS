package app.clearsms.domain.parser

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * The bundled guard library: the asset copy must be byte-identical to the
 * `rules/guards.json` master, the guard ids in data and the [GuardId] enum
 * must correspond exactly (and every id must be consulted somewhere in
 * production code), each guard must fire on the message shapes that motivated
 * it, and a malformed document, an invalid pattern, or a pattern violating
 * the ReDoS rules must degrade to a guard that never matches - never crash.
 */
class GuardLibraryTest {
    // region rules/ <-> assets identity and id coverage

    @Test
    fun `guards master and bundled copy are identical`() {
        val master = repoFile("rules/guards.json")
        val bundled = repoFile("app/src/main/assets/guards/guards.json")
        assertThat(bundled.readText()).isEqualTo(master.readText())
    }

    @Test
    fun `every GuardId is present in the bundled document and vice versa`() {
        val dataIds =
            Regex("\"id\"\\s*:\\s*\"([a-z_]+)\"")
                .findAll(repoFile("rules/guards.json").readText())
                .map { it.groupValues[1] }
                .toSet()
        val enumIds = GuardId.entries.map { it.id }.toSet()
        assertWithMessage("guards.json ids without a GuardId enum constant (orphan data)")
            .that(dataIds - enumIds)
            .isEmpty()
        assertWithMessage("GuardId constants missing from guards.json (orphan code)")
            .that(enumIds - dataIds)
            .isEmpty()
    }

    @Test
    fun `every GuardId is consulted by production code`() {
        val sources =
            File("src/main/kotlin")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.name != "GuardLibrary.kt" }
                .joinToString("\n") { it.readText() }
        val unreferenced = GuardId.entries.filter { !sources.contains("GuardId.${it.name}") }
        assertWithMessage("GuardId constants never consulted outside the library itself")
            .that(unreferenced)
            .isEmpty()
    }

    // endregion

    // region per-guard behaviour (the fixtures that motivated each guard)

    @Test
    fun `statement notice guard fires on statement delivery notices and scrubs them`() {
        assertThat(matches(GuardId.STATEMENT_NOTICE, "Statement is sent to your registered email")).isTrue()
        assertThat(matches(GuardId.STATEMENT_NOTICE, "E-statement of your Credit Card XX1234 has been mailed")).isTrue()
        assertThat(matches(GuardId.STATEMENT_NOTICE, "Your statement is now available in the app")).isTrue()
        assertThat(matches(GuardId.STATEMENT_NOTICE, "Rs.500 debited from A/c XX1234")).isFalse()
        val scrubbed = GuardLibrary.scrub(GuardId.STATEMENT_NOTICE, "Statement is sent to you. Total of Rs 100 is due")
        assertThat(scrubbed).doesNotContain("sent")
        assertThat(scrubbed).contains("Total of Rs 100 is due")
    }

    @Test
    fun `bill due notice guard fires on future obligations`() {
        assertThat(
            matches(GuardId.BILL_DUE_NOTICE, "Payment of INR 532.62 for your Axis Bank Credit Card is due on 04-04-26"),
        ).isTrue()
        assertThat(matches(GuardId.BILL_DUE_NOTICE, "your broadband bill of Rs.1178.82 for 1550 is due on 10-Jun-26")).isTrue()
        assertThat(matches(GuardId.BILL_DUE_NOTICE, "Payment of INR 532.62 received. Thank you")).isFalse()
    }

    @Test
    fun `failed payment guard fires on each failure phrasing`() {
        assertThat(matches(GuardId.FAILED_PAYMENT, "Your payment of Rs.500 has failed. If debited it will be refunded")).isTrue()
        assertThat(matches(GuardId.FAILED_PAYMENT, "Transaction was declined by your bank")).isTrue()
        assertThat(matches(GuardId.FAILED_PAYMENT, "Recharge of Rs.239 was unsuccessful")).isTrue()
        assertThat(matches(GuardId.FAILED_PAYMENT, "Your txn could not be processed")).isTrue()
        assertThat(matches(GuardId.FAILED_PAYMENT, "Payment of Rs.500 done successfully")).isFalse()
    }

    @Test
    fun `settled payment guard fires on completed events`() {
        assertThat(matches(GuardId.SETTLED_PAYMENT, "We have received your payment of Rs.5,000")).isTrue()
        assertThat(matches(GuardId.SETTLED_PAYMENT, "Thank You for an online payment of Rs.549.00")).isTrue()
        assertThat(matches(GuardId.SETTLED_PAYMENT, "Claim no. 12345 has been settled")).isTrue()
        assertThat(matches(GuardId.SETTLED_PAYMENT, "Rs 1000 refunded to your account")).isTrue()
        assertThat(matches(GuardId.SETTLED_PAYMENT, "Total due Rs.15,240 pay by 05-08-26")).isFalse()
    }

    @Test
    fun `marketing pitch guard fires on upsell language`() {
        assertThat(matches(GuardId.MARKETING_PITCH, "investment in ULIPs can reap benefits of rising markets")).isTrue()
        assertThat(matches(GuardId.MARKETING_PITCH, "Best time to invest for wealth creation!")).isTrue()
        assertThat(matches(GuardId.MARKETING_PITCH, "premium of Rs 5000 is due on 05-May-26")).isFalse()
    }

    @Test
    fun `voucher guard fires on coupon and voucher grants`() {
        assertThat(matches(GuardId.VOUCHER, "Your Rs.500 voucher from your Credit Card expires on 30-09-26")).isTrue()
        assertThat(matches(GuardId.VOUCHER, "Use promo code SAVE20 by 31-08-26")).isTrue()
        assertThat(matches(GuardId.VOUCHER, "Your card bill is due on 30-09-26")).isFalse()
    }

    @Test
    fun `mandate notice guard fires on UPI mandate lifecycle notices`() {
        assertThat(matches(GuardId.MANDATE_NOTICE, "Mandate for Rs.649.00 towards Netflix successfully created")).isTrue()
        assertThat(matches(GuardId.MANDATE_NOTICE, "You have successfully cancelled the scheduled monthly payment")).isTrue()
        assertThat(matches(GuardId.MANDATE_NOTICE, "Rs.649 debited for Netflix via mandate")).isFalse()
    }

    @Test
    fun `collect request guard fires on payment requests but not on executed debits`() {
        // The OCR'd device fixture shape (synthetic company/amount).
        assertThat(
            matches(
                GuardId.COLLECT_REQUEST,
                "You've received an IPO request from EXAMPLE TRANSMISSION LIMITED for up to Rs.14807. Click to accept.",
            ),
        ).isTrue()
        assertThat(matches(GuardId.COLLECT_REQUEST, "RAMESH KUMAR has requested Rs.500.00 from your account. Approve in app")).isTrue()
        assertThat(matches(GuardId.COLLECT_REQUEST, "You have declined the payment request from RAMESH KUMAR")).isTrue()
        assertThat(matches(GuardId.COLLECT_REQUEST, "Approve to pay Rs.649 towards your subscription")).isTrue()
        assertThat(matches(GuardId.COLLECT_REQUEST, "Rs.14807 blocked for IPO of EXAMPLE TRANSMISSION LIMITED via UPI mandate")).isTrue()
        // Executed mandates and genuine credits must NOT match.
        assertThat(
            matches(GuardId.COLLECT_REQUEST, "Amount blocked for IPO of EXAMPLE LTD has been debited from your A/c XX1234"),
        ).isFalse()
        assertThat(matches(GuardId.COLLECT_REQUEST, "Rs.2,000.00 received in your A/c XX1234 from RAMESH KUMAR via UPI")).isFalse()
        assertThat(matches(GuardId.COLLECT_REQUEST, "Rs.649 debited for Netflix via mandate")).isFalse()
    }

    @Test
    fun `limit offer guard fires on limit increase offers`() {
        assertThat(matches(GuardId.LIMIT_OFFER, "You are eligible for a Credit Limit increase to Rs.3,00,000")).isTrue()
        assertThat(matches(GuardId.LIMIT_OFFER, "Pre-approved limit enhancement. Apply now")).isTrue()
        assertThat(matches(GuardId.LIMIT_OFFER, "The credit limit for your Card has been changed from INR 100000 to INR 150000")).isFalse()
    }

    @Test
    fun `tier premium guard fires on product tiers not insurance premiums`() {
        assertThat(matches(GuardId.TIER_PREMIUM, "Your LIV Premium subscription is confirmed")).isTrue()
        assertThat(matches(GuardId.TIER_PREMIUM, "YouTube Premium plan renews on 05-08-26")).isTrue()
        assertThat(matches(GuardId.TIER_PREMIUM, "premium of Rs.24,000 is due on your policy")).isFalse()
    }

    @Test
    fun `future tense guard fires only at the end of the inspected window`() {
        assertThat(matches(GuardId.FUTURE_TENSE, "an amount of Rs 4131 will be ")).isTrue()
        assertThat(matches(GuardId.FUTURE_TENSE, "shall be")).isTrue()
        assertThat(matches(GuardId.FUTURE_TENSE, "will be deducted tomorrow")).isFalse()
    }

    @Test
    fun `instruction start guard fires on call-to-action candidates`() {
        assertThat(matches(GuardId.INSTRUCTION_START, "know the transaction status")).isTrue()
        assertThat(matches(GuardId.INSTRUCTION_START, "avoid late fees")).isTrue()
        assertThat(matches(GuardId.INSTRUCTION_START, "Amazon Pay India")).isFalse()
    }

    // endregion

    // region degradation: malformed data must never crash

    @Test
    fun `malformed document degrades to an empty library`() {
        assertThat(GuardLibrary.parse("{ not json")).isEmpty()
        assertThat(GuardLibrary.parse(null)).isEmpty()
        assertThat(GuardLibrary.parse("""{"guards": "wrong shape"}""")).isEmpty()
    }

    @Test
    fun `invalid pattern is skipped without taking down its siblings`() {
        val parsed =
            GuardLibrary.parse(
                """{"guards":[{"id":"voucher","patterns":["broken(","(?i)\\bvouchers?\\b"]}]}""",
            )
        val patterns = parsed[GuardId.VOUCHER]!!
        assertThat(patterns).hasSize(1)
        assertThat(patterns.single().containsMatchIn("your voucher")).isTrue()
    }

    @Test
    fun `unknown guard id in data is skipped and a missing guard never matches`() {
        val parsed = GuardLibrary.parse("""{"guards":[{"id":"no_such_guard","patterns":["x"]}]}""")
        assertThat(parsed).isEmpty()
        // A guard absent from the document degrades to never-match via matches().
        assertThat(GuardLibrary.anyMatch("missing", emptyList(), "anything")).isFalse()
    }

    // endregion

    // region load-time ReDoS validation

    @Test
    fun `patterns with leading or trailing wildcard wrappers are rejected`() {
        assertThat(GuardLibrary.validate(""".*failed""")).isNotNull()
        assertThat(GuardLibrary.validate("""(?i).*failed""")).isNotNull()
        assertThat(GuardLibrary.validate("""[\s\S]*failed""")).isNotNull()
        assertThat(GuardLibrary.validate("""failed.*""")).isNotNull()
        assertThat(GuardLibrary.validate("""failed[\s\S]+$""")).isNotNull()
    }

    @Test
    fun `patterns with nested unbounded quantifiers are rejected`() {
        assertThat(GuardLibrary.validate("""(a+)+b""")).isNotNull()
        assertThat(GuardLibrary.validate("""(?:\d+,?)*x""")).isNotNull()
        assertThat(GuardLibrary.validate("""(?:ab|cd*)+""")).isNotNull()
    }

    @Test
    fun `variable length lookbehind and oversized patterns are rejected`() {
        assertThat(GuardLibrary.validate("""(?<=ab+)x""")).isNotNull()
        assertThat(GuardLibrary.validate("a".repeat(513))).isNotNull()
        assertThat(GuardLibrary.validate("")).isNotNull()
    }

    @Test
    fun `the real guard shapes pass validation`() {
        // Bounded gaps, bounded groups, classes with literal quantifier chars,
        // fixed-length lookbehind, anchors: all legitimate.
        assertThat(GuardLibrary.validate("""(?i)\bmandate\b[\s\S]{0,60}?\bcreated\b""")).isNull()
        assertThat(GuardLibrary.validate("""(?i)[\d,]+(?:\.\d{1,2})?[^\n]{0,100}?\bis\s+due\b""")).isNull()
        assertThat(GuardLibrary.validate("""(?i)\b[\w&+.]+\s+premium\b""")).isNull()
        assertThat(GuardLibrary.validate("""(?<!\d)x""")).isNull()
        assertThat(GuardLibrary.validate("""(?i)\b(?:will|shall|would)\s+be\s*$""")).isNull()
        // Every pattern actually shipped must survive validation and compile:
        // the number of compiled patterns equals the number in the document.
        val master = repoFile("rules/guards.json").readText()
        val declared = Regex("\"\\(\\?i\\)").findAll(master).count() + Regex("\"\\(\\?<").findAll(master).count()
        val compiled = GuardLibrary.parse(master).values.sumOf { it.size }
        assertThat(compiled).isEqualTo(declared)
        assertThat(compiled).isAtLeast(GuardId.entries.size)
    }

    @Test
    fun `every bundled guard loads at least one usable pattern`() {
        for (id in GuardId.entries) {
            // A guard whose patterns were all rejected would silently stop
            // vetoing; catch that here rather than in the field.
            assertWithMessage("guard '${id.id}' fires on nothing at all - patterns rejected at load?")
                .that(sampleFor(id).any { matches(id, it) })
                .isTrue()
        }
    }

    // endregion

    // region evaluation budget and input cap

    @Test
    fun `evaluation budget bounds a pathological pattern list`() {
        // Fake clock: the budget is exhausted after the first pattern, so the
        // second (which WOULD match) must never be consulted.
        var calls = 0
        val clock = { if (calls++ == 0) 0L else Long.MAX_VALUE / 2 }
        val patterns = listOf(Regex("zzz-never"), Regex("hit"))
        assertThat(GuardLibrary.anyMatch("test", patterns, "hit", budgetNanos = 1L, nanoTime = clock)).isFalse()
        // With an untouched budget the same list matches.
        assertThat(GuardLibrary.anyMatch("test", patterns, "hit")).isTrue()
    }

    @Test
    fun `matches caps the evaluated input length so a huge body is bounded`() {
        val padding = "x".repeat(GuardLibrary.MAX_INPUT_LENGTH + 100)
        // The failure phrase sits beyond the cap: never evaluated.
        assertThat(matches(GuardId.FAILED_PAYMENT, "$padding your payment has failed")).isFalse()
        // Within the cap it fires as usual.
        assertThat(matches(GuardId.FAILED_PAYMENT, "your payment has failed $padding")).isTrue()
    }

    // endregion

    private fun matches(
        id: GuardId,
        text: String,
    ): Boolean = GuardLibrary.matches(id, text)

    private fun sampleFor(id: GuardId): List<String> =
        when (id) {
            GuardId.STATEMENT_NOTICE -> listOf("Statement is sent to your email")
            GuardId.BILL_DUE_NOTICE -> listOf("Payment of INR 532.62 for your Card is due on 04-04-26")
            GuardId.FAILED_PAYMENT -> listOf("Your payment has failed")
            GuardId.SETTLED_PAYMENT -> listOf("We have received your payment")
            GuardId.MARKETING_PITCH -> listOf("reap benefits of rising markets")
            GuardId.VOUCHER -> listOf("your voucher expires soon")
            GuardId.MANDATE_NOTICE -> listOf("Mandate successfully created")
            GuardId.COLLECT_REQUEST -> listOf("You've received a payment request from EXAMPLE for Rs.100")
            GuardId.RETIREMENT_UNITS_ECHO ->
                listOf("Your contribution of Rs.50,000.00 has been credited to your NPS Tier-I a/c")
            GuardId.HYPOTHETICAL_AMOUNT -> listOf("earn 3 pts on every Rs 100 spent")
            GuardId.LIMIT_OFFER -> listOf("you are eligible for an increase")
            GuardId.TIER_PREMIUM -> listOf("LIV Premium subscription active")
            GuardId.FUTURE_TENSE -> listOf("amount will be ")
            GuardId.INSTRUCTION_START -> listOf("know the transaction status")
            GuardId.PAYOUT_IN_FLIGHT ->
                listOf("Refund of Rs.1,240.50 towards credit balance has been initiated")
            GuardId.FINANCIAL_EVIDENCE ->
                listOf("SIP instalment of Rs.2,500.00 in Folio 5023/47 has been processed")
        }

    private fun repoFile(repoRelativePath: String): File =
        sequenceOf(
            File(repoRelativePath),
            File("..", repoRelativePath),
            File(repoRelativePath.removePrefix("app/")),
        ).first(File::exists)
}
