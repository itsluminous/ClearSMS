package app.clearsms.domain.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OtpParserTest {
    private val parser = OtpParser()

    @Test
    fun `otp is keyword pattern`() {
        val result = parser.parse("482910 OTP is 482910 for your login. Do not share it with anyone.")
        assertThat(result?.code).isEqualTo("482910")
    }

    @Test
    fun `otp with colon`() {
        val result = parser.parse("Your OTP: 7391 for transaction at merchant.")
        assertThat(result?.code).isEqualTo("7391")
    }

    @Test
    fun `code is pattern`() {
        val result = parser.parse("Your verification code is 55391 to complete signup.")
        assertThat(result?.code).isEqualTo("55391")
    }

    @Test
    fun `digits first pattern`() {
        val result = parser.parse("934820 is your OTP for HDFC Bank NetBanking login.")
        assertThat(result?.code).isEqualTo("934820")
    }

    @Test
    fun `digits is the verification pattern`() {
        val result = parser.parse("558123 is the verification code for your account.")
        assertThat(result?.code).isEqualTo("558123")
    }

    @Test
    fun `use digits to pattern`() {
        val result = parser.parse("Use 448291 to verify your mobile number.")
        assertThat(result?.code).isEqualTo("448291")
    }

    @Test
    fun `enter digits for pattern`() {
        val result = parser.parse("Please enter 90218 for confirming your booking.")
        assertThat(result?.code).isEqualTo("90218")
    }

    @Test
    fun `password keyword`() {
        val result = parser.parse("Your one time password is 66502. Valid for 10 minutes.")
        assertThat(result?.code).isEqualTo("66502")
    }

    @Test
    fun `pin keyword`() {
        val result = parser.parse("Your PIN is 4432 for card activation.")
        assertThat(result?.code).isEqualTo("4432")
    }

    @Test
    fun `bare six digits with verification context`() {
        val result = parser.parse("293841 - complete your login within 5 minutes.")
        assertThat(result?.code).isEqualTo("293841")
    }

    @Test
    fun `bare six digits without context is not otp`() {
        val result = parser.parse("Your order 293841 has been shipped and will arrive tomorrow.")
        assertThat(result).isNull()
    }

    @Test
    fun `transaction message is not otp`() {
        val result = parser.parse("Rs.2500.00 debited from A/c XX1234 on 15-07-26. Avl Bal Rs.10000.")
        assertThat(result).isNull()
    }

    @Test
    fun `plain conversation is not otp`() {
        val result = parser.parse("Hey, are we still meeting at 6 today?")
        assertThat(result).isNull()
    }

    @Test
    fun `promotional message with number is not otp`() {
        val result = parser.parse("Get flat 50% off! Shop for Rs.1999 or more. T&C apply.")
        assertThat(result).isNull()
    }

    @Test
    fun `four digit otp extracted`() {
        val result = parser.parse("OTP 8821 for payment of Rs.150 at Metro. Do not share.")
        assertThat(result?.code).isEqualTo("8821")
    }

    @Test
    fun `eight digit otp extracted`() {
        val result = parser.parse("Your OTP is 48291045 for Aadhaar authentication.")
        assertThat(result?.code).isEqualTo("48291045")
    }

    @Test
    fun `authorisation code with is-colon separator - the exact reported wording`() {
        // Issue #1 comment (Ergo Hestia), synthetic code: "is:" was falling
        // through the anchored patterns because the separator allowed only
        // "is" OR ":", never both.
        val result = parser.parse("Your authorisation code is: 1234")
        assertThat(result?.code).isEqualTo("1234")
    }

    @Test
    fun `authorization code american spelling anchors too`() {
        val result = parser.parse("Your authorization code is: 987654")
        assertThat(result?.code).isEqualTo("987654")
    }

    @Test
    fun `authorisation code anchors on the strict anchored-only path`() {
        // parseAnchored is what beats a transaction categorization and what
        // notification extraction trusts - the fix must hold there, not
        // just in the contextual fallback.
        assertThat(parser.parseAnchored("Your authorisation code is: 1234")?.code).isEqualTo("1234")
        assertThat(parser.parseAnchored("Your authorization code is: 5678")?.code).isEqualTo("5678")
    }

    @Test
    fun `bare four digit number is still not an otp`() {
        // Near-miss: four digits with no anchoring keyword must stay
        // unmatched - the bare-number fallback accepts SIX digits only.
        assertThat(parser.parse("Your bill of 1234 is generated for this month.")).isNull()
        // Even WITH a verification-context word in the body, an unanchored
        // four-digit number is not a code.
        assertThat(parser.parse("Never share any code with anyone. Ticket 1234 raised.")).isNull()
    }
}
