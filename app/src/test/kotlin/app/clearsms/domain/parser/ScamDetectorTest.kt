package app.clearsms.domain.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScamDetectorTest {
    private val detector = ScamDetector()

    @Test
    fun `shortener with prize bait is scam`() {
        assertThat(
            detector.isScam("Congratulations! You have won a lottery of Rs.10,00,000. Claim now at bit.ly/win123"),
        ).isTrue()
    }

    @Test
    fun `shortener with kyc bait is scam`() {
        assertThat(
            detector.isScam("Dear user your KYC is pending. Complete at tinyurl.com/kyc-fix"),
        ).isTrue()
    }

    @Test
    fun `kyc urgency with link is scam`() {
        assertThat(
            detector.isScam("Your KYC will expire today. Update immediately at http://kyc-update.example.com to avoid suspension"),
        ).isTrue()
    }

    @Test
    fun `prize with claim call to action is scam even without link`() {
        assertThat(
            detector.isScam("You have won a lucky draw prize! Call now to claim your reward."),
        ).isTrue()
    }

    @Test
    fun `legit bank debit alert is not scam`() {
        assertThat(
            detector.isScam("Rs.500 debited from A/c XX1234 on 12-07-26. Avl Bal Rs.1000. Call 18002586161 for dispute."),
        ).isFalse()
    }

    @Test
    fun `legit otp is not scam`() {
        assertThat(detector.isScam("Your OTP is 123456. Do not share it with anyone.")).isFalse()
    }

    @Test
    fun `promo with normal link is not scam`() {
        assertThat(detector.isScam("Weekend sale! 50% off on everything at www.example.com")).isFalse()
    }

    @Test
    fun `shortener without bait is not scam`() {
        assertThat(detector.isScam("Check out bit.ly/menu2026 for our new menu")).isFalse()
    }
}
