package app.clearsms.domain.rules

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RuleSuggesterTest {
    private val debitSms =
        "Rs.2,500.00 debited from a/c XX3456 on 12-07-25 to VPA merchant@okicici. " +
            "UPI Ref 519876543210. Avl Bal Rs.10,250.50"

    @Test
    fun `bank debit sms detects amount account date reference and balance`() {
        val tokens = RuleSuggester.suggest(debitSms)
        val byKind = tokens.groupBy { it.kind }
        assertThat(byKind[TokenKind.AMOUNT]?.map { it.literal }).containsExactly("2,500.00")
        assertThat(byKind[TokenKind.ACCOUNT_LAST4]?.map { it.literal }).containsExactly("3456")
        assertThat(byKind[TokenKind.DATE]?.map { it.literal }).containsExactly("12-07-25")
        assertThat(byKind[TokenKind.REFERENCE]?.map { it.literal }).containsExactly("519876543210")
        assertThat(byKind[TokenKind.BALANCE]?.map { it.literal }).containsExactly("10,250.50")
    }

    @Test
    fun `balance amount is claimed by BALANCE not by plain AMOUNT`() {
        val tokens = RuleSuggester.suggest(debitSms)
        val amounts = tokens.filter { it.kind == TokenKind.AMOUNT }.map { it.literal }
        assertThat(amounts).doesNotContain("10,250.50")
    }

    @Test
    fun `upi vpa handle is the top ranked vendor candidate`() {
        val vendors = RuleSuggester.suggest(debitSms).filter { it.kind == TokenKind.VENDOR }.sortedBy { it.rank }
        assertThat(vendors.first().literal).isEqualTo("merchant@okicici")
        assertThat(vendors.first().suggestedField).isEqualTo(RuleSuggester.Fields.MERCHANT)
    }

    @Test
    fun `otp sms detects the code nearest the verification context`() {
        val tokens = RuleSuggester.suggest("483920 is your OTP for netbanking login. Valid for 10 mins.")
        val otp = tokens.single { it.kind == TokenKind.OTP_CODE }
        assertThat(otp.literal).isEqualTo("483920")
        assertThat(otp.suggestedField).isEqualTo(RuleSuggester.Fields.OTP_CODE)
        // The stray "10" must still be surfaced as a digit group.
        assertThat(tokens.filter { it.kind == TokenKind.GENERIC_NUMBER }.map { it.literal }).contains("10")
    }

    @Test
    fun `no otp token without a verification context`() {
        val tokens = RuleSuggester.suggest("Your parcel weighs 4520 grams")
        assertThat(tokens.none { it.kind == TokenKind.OTP_CODE }).isTrue()
        assertThat(tokens.single { it.kind == TokenKind.GENERIC_NUMBER }.literal).isEqualTo("4520")
    }

    @Test
    fun `credit sms with month date detects date and credited keyword`() {
        val tokens =
            RuleSuggester.suggest("INR 15,000.00 credited to A/c XX9921 on 01-Aug-25 by NEFT from ACME Ref AXISN12345678")
        assertThat(tokens.single { it.kind == TokenKind.DATE }.literal).isEqualTo("01-Aug-25")
        assertThat(tokens.single { it.kind == TokenKind.REFERENCE }.literal).isEqualTo("AXISN12345678")
        assertThat(tokens.filter { it.kind == TokenKind.KEYWORD }.map { it.literal }).contains("credited")
        assertThat(tokens.filter { it.kind == TokenKind.VENDOR }.map { it.literal }).contains("ACME")
    }

    @Test
    fun `card bill sms suggests due_date for the date token`() {
        val tokens = RuleSuggester.suggest("Your card bill of Rs.4,321.09 is due on 15-08-2025. Min due Rs.500.")
        val date = tokens.single { it.kind == TokenKind.DATE }
        assertThat(date.literal).isEqualTo("15-08-2025")
        assertThat(date.suggestedField).isEqualTo(RuleSuggester.Fields.DUE_DATE)
        assertThat(tokens.filter { it.kind == TokenKind.KEYWORD }.map { it.literal }).containsAtLeast("due", "bill")
    }

    @Test
    fun `delivery sms detects brand vendor and delivered keyword`() {
        val tokens = RuleSuggester.suggest("Your Amazon package has been delivered. Rate your experience.")
        val vendors = tokens.filter { it.kind == TokenKind.VENDOR }.sortedBy { it.rank }
        assertThat(vendors.first().literal).isEqualTo("Amazon")
        assertThat(tokens.filter { it.kind == TokenKind.KEYWORD }.map { it.literal }).contains("delivered")
    }

    @Test
    fun `post-preposition vendor outranks random capitalized words`() {
        val tokens = RuleSuggester.suggest("Payment of Rs.500 to Chaipoint successful. Enjoy Great Offers Today")
        val vendors = tokens.filter { it.kind == TokenKind.VENDOR }.sortedBy { it.rank }.map { it.literal }
        assertThat(vendors.first()).isEqualTo("Chaipoint")
    }

    @Test
    fun `vendor candidates are capped at five`() {
        val tokens = RuleSuggester.suggest("Alpha Bravo Chocolate Delta Echoes Foxtrot Golfer met yesterday")
        assertThat(tokens.count { it.kind == TokenKind.VENDOR }).isAtMost(5)
    }

    @Test
    fun `every digit group is enumerated exactly once`() {
        val tokens = RuleSuggester.suggest("Use 4111 2222 3333 by 31/12")
        val numeric = tokens.filter { it.kind == TokenKind.GENERIC_NUMBER || it.kind == TokenKind.DATE }
        assertThat(numeric.map { it.literal }).containsExactly("4111", "2222", "3333", "31/12")
        // Spans never overlap: each digit is owned by one token.
        val sorted = numeric.sortedBy { it.start }
        sorted.zipWithNext().forEach { (a, b) -> assertThat(a.end).isAtMost(b.start) }
    }

    @Test
    fun `percent detection`() {
        val tokens = RuleSuggester.suggest("Get 50% off on your next recharge")
        assertThat(tokens.single { it.kind == TokenKind.PERCENT }.literal).isEqualTo("50")
        assertThat(tokens.filter { it.kind == TokenKind.KEYWORD }.map { it.literal }).contains("recharge")
    }

    @Test
    fun `every capture fragment compiles with exactly one capture group`() {
        val samples = listOf(debitSms, "483920 is your OTP", "Get 50% off", "paid to a@b on 01-Jan-25 Ref ABC123456")
        samples
            .flatMap { RuleSuggester.suggest(it) }
            .filter { it.kind != TokenKind.KEYWORD }
            .forEach { token ->
                assertThat(RuleComposer.captureGroupCount(token.captureFragment)).isEqualTo(1)
            }
    }

    @Test
    fun `sender pattern strips trai route prefix and suffix and escapes metacharacters`() {
        assertThat(RuleSuggester.senderPattern("VM-HDFCBK-S")).isEqualTo("(?i)HDFCBK")
        val regex = Regex(RuleSuggester.senderPattern("VM-HDFCBK-S"))
        assertThat(regex.containsMatchIn("AD-HDFCBK")).isTrue()
        assertThat(regex.containsMatchIn("hdfcbk")).isTrue()
        assertThat(regex.containsMatchIn("ICICIB")).isFalse()

        val escaped = RuleSuggester.senderPattern("AX-AB.CD")
        assertThat(escaped).isEqualTo("(?i)AB\\.CD")
        assertThat(Regex(escaped).containsMatchIn("AB.CD")).isTrue()
        assertThat(Regex(escaped).containsMatchIn("ABXCD")).isFalse()
    }
}
