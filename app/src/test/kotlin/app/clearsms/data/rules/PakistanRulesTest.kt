package app.clearsms.data.rules

import app.clearsms.domain.categorizer.ContactLookup
import app.clearsms.domain.categorizer.MessageCategorizer
import app.clearsms.domain.categorizer.SenderIdLookup
import app.clearsms.domain.model.Category
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File

/**
 * Guards for the Pakistan rules under `rules/pakistan`.
 *
 * Two of these pin findings from the review of the PR that added them, and
 * both failure modes are the kind that only hurt users in OTHER countries -
 * the rules work fine in Pakistan either way, so nothing but a test will
 * notice a regression.
 *
 * 1. Sender patterns must not match a short bank abbreviation as a SUBSTRING.
 *    `|HBL` matched every sender containing "HBL", and the bundled Indian
 *    sender directory holds 223 registered headers that do - `GSMHBL` (a
 *    maternity hospital), `ESHBLR` (a student helpline), `ANSHBL` (a trader).
 *    Bundled rules are consulted before the sender directory, so such a rule
 *    outranks what the app already knows about the sender.
 * 2. A promotional rule keyed on a noun must not swallow transactional
 *    messages that use the same noun. `\bloan\b` filed loan disbursals and
 *    overdue notices as Promotional - money that moved, and the single most
 *    important message a borrower gets.
 */
class PakistanRulesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun bundledRules(): List<RuleDefinition> {
        val file =
            listOf(
                File("src/main/assets/default_rules.json"),
                File("app/src/main/assets/default_rules.json"),
            ).firstOrNull { it.exists() }
        checkNotNull(file) { "default_rules.json asset not found" }
        return json.decodeFromString(RuleDocument.serializer(), file.readText()).rules
    }

    private fun categorizer() =
        MessageCategorizer(
            ruleEngine = RuleEngine(),
            senderIdLookup = SenderIdLookup { null },
            contactLookup = ContactLookup { false },
        )

    private fun categorize(
        sender: String,
        body: String,
    ) = categorizer().categorize(
        sender = sender,
        body = body,
        userRules = emptyList(),
        builtinRules = bundledRules(),
    )

    // ---- Finding 1: short abbreviations must be anchored, not substrings ----

    @Test
    fun `an indian sender containing HBL does not match the HBL rules`() {
        // Real registered header shapes from the bundled Indian directory.
        val senders = listOf("VM-GSMHBL-S", "AD-ESHBLR-P", "JD-ANSHBL-S", "GSMHBL", "ESHBLR")
        val bodies =
            listOf(
                "Dear Patient, your appointment has been successfully registered for 02-Nov.",
                "Your account has been activated. Login at the portal to continue.",
                "Your cheque book request is ready for collection.",
            )
        for (sender in senders) {
            for (body in bodies) {
                val result = categorize(sender, body)
                assertThat(result.matchedRuleId).isNotEqualTo("hbl-service-notice-01")
                assertThat(result.matchedRuleId).isNotEqualTo("hbl-otp-01")
                assertThat(result.extracted["bank"]).isNotEqualTo("HBL")
            }
        }
    }

    @Test
    fun `an OTP from an indian sender containing HBL is not branded HBL`() {
        val result = categorize("JD-ANSHBL-S", "482913 is your OTP to login. Do not share.")
        // The OTP category is right either way; the bank label must not be.
        assertThat(result.extracted["bank"]).isNotEqualTo("HBL")
        assertThat(result.matchedRuleId).isNotEqualTo("hbl-otp-01")
    }

    @Test
    fun `HBL's own senders still match`() {
        for (sender in listOf("4250", "14250", "HBL", "HBL Pay")) {
            val result = categorize(sender, "482913 is your OTP for your card transaction.")
            assertThat(result.category).isEqualTo(Category.OTP)
            assertThat(result.extracted["bank"]).isEqualTo("HBL")
        }
    }

    @Test
    fun `no pakistan sender pattern matches a bank abbreviation as a bare substring`() {
        // An unanchored alternative shorter than five characters is almost
        // always a collision waiting to happen in a DLT header namespace.
        // Scoped to these rules: the same check across the whole bundle
        // currently flags `sfx-code-01`, which predates this file.
        for (rule in bundledRules().filter {
            it.id.startsWith("meezan-") || it.id.startsWith("hbl-") || it.id.startsWith("jazzcash-")
        }) {
            val pattern = rule.match.senderPattern ?: continue
            for (alternative in pattern.removePrefix("(?i)").split("|")) {
                val bare = alternative.trim().removePrefix("(?:").removeSuffix(")")
                val isLiteral = bare.all { it.isLetterOrDigit() }
                val anchored = alternative.contains("^") || alternative.contains("$")
                if (isLiteral && bare.length in 1..4 && !anchored) {
                    throw AssertionError(
                        "Rule '${rule.id}' has unanchored short literal '$bare' in its sender " +
                            "pattern; anchor it as ^$bare$ or it will match longer sender ids " +
                            "that merely contain it.",
                    )
                }
            }
        }
    }

    // ---- Finding 2: a promotional noun must not swallow transactions ----

    @Test
    fun `jazzcash loan status messages are not promotional`() {
        val transactional =
            listOf(
                "Your loan of Rs 5,000 has been disbursed to your JazzCash account. Repay by 15-11-2026.",
                "Your loan is overdue. Please repay to avoid penalty charges.",
                "Congratulations! Your loan request has been approved. Amount will be transferred shortly.",
                "Rs 5,000 loan has been credited to your JazzCash account. Trx ID 9988776655.",
                "Rs 5,250 has been deducted from your JazzCash account for loan repayment.",
            )
        for (body in transactional) {
            val result = categorize("8558", body)
            assertThat(result.category).isNotEqualTo(Category.PROMOTIONAL)
            assertThat(result.matchedRuleId).isNotEqualTo("jazzcash-loan-offer-01")
        }
    }

    @Test
    fun `jazzcash loan pitches are still promotional`() {
        val pitches =
            listOf(
                "Get a JazzCash loan up to Rs 10,000 instantly! Dial *786# to apply now.",
                "You are eligible for a JazzCash cash loan. T&Cs apply.",
            )
        for (body in pitches) {
            val result = categorize("8558", body)
            assertThat(result.category).isEqualTo(Category.PROMOTIONAL)
        }
    }

    @Test
    fun `a jazzcash loan OTP is never promotional`() {
        for (body in listOf(
            "Your OTP for loan request is 445566.",
            "Your verification code for loan request is 445566.",
        )) {
            val result = categorize("8558", body)
            assertThat(result.category).isEqualTo(Category.OTP)
        }
    }

    // ---- Pattern hygiene for the rules this file covers ----

    @Test
    fun `pakistan patterns have no ambiguous lazy run between whitespace gaps`() {
        // `\s+([^\n]{1,30}?)\s+` is ambiguous on a run of spaces: the engine
        // can split it in N ways and backtracks through all of them. Starting
        // the capture with a non-space makes the split unique.
        val ambiguous = Regex("""\\s\+\(?\[\^\\n]\{1,\d+}\?\\s\+""")
        val offenders =
            bundledRules()
                .filter { it.id.startsWith("meezan-") || it.id.startsWith("hbl-") || it.id.startsWith("jazzcash-") }
                .filter { rule -> rule.match.bodyPattern?.let { ambiguous.containsMatchIn(it) } == true }
                .map { it.id }
        assertThat(offenders).isEmpty()
    }

    @Test
    fun `a whitespace padded body does not stall the meezan patterns`() {
        val hostile =
            "PKR 1 charged at " + " ".repeat(500) +
                "as internet purchase, from A/C " + "X".repeat(400)
        val startNs = System.nanoTime()
        categorize("8079", hostile)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
        // The ambiguous form took ~150ms per pattern and tripped RuleEngine's
        // 250ms budget; the anchored form is microseconds. The ceiling is
        // generous so a slow CI machine cannot flake it.
        assertThat(elapsedMs).isLessThan(1_000)
    }
}
