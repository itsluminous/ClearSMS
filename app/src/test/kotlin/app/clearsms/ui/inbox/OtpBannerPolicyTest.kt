package app.clearsms.ui.inbox

import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.model.Category
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OtpBannerPolicyTest {
    private val now = 1_753_600_000_000L

    private fun otpMessage(
        id: Long,
        threadId: Long = 7,
        code: String? = "482910",
        ageMs: Long = 0,
    ) = MessageEntity(
        id = id,
        threadId = threadId,
        sender = "VM-HDFCBK",
        normalizedSender = "HDFCBK",
        body = "$code is your OTP",
        timestamp = now - ageMs,
        category = Category.OTP,
        extractedOtp = code,
    )

    @Test
    fun `fresh unhandled otp shows`() {
        val picked = OtpBannerPolicy.select(listOf(otpMessage(id = 10)), handledMessageId = 0, nowMs = now)
        assertThat(picked?.id).isEqualTo(10)
    }

    @Test
    fun `otp is hidden after it was copied`() {
        val picked = OtpBannerPolicy.select(listOf(otpMessage(id = 10)), handledMessageId = 10, nowMs = now)
        assertThat(picked).isNull()
    }

    @Test
    fun `otp is hidden after it was dismissed`() {
        val messages = listOf(otpMessage(id = 12), otpMessage(id = 8, ageMs = 60_000))
        // Dismissing the newest hides it AND anything older.
        assertThat(OtpBannerPolicy.select(messages, handledMessageId = 12, nowMs = now)).isNull()
    }

    @Test
    fun `handled otp stays hidden across restart via the persisted id`() {
        // Fresh policy evaluation (new process) with only the persisted id.
        val persistedHandledId = 42L
        val picked =
            OtpBannerPolicy.select(
                listOf(otpMessage(id = 42, ageMs = 30_000)),
                handledMessageId = persistedHandledId,
                nowMs = now,
            )
        assertThat(picked).isNull()
    }

    @Test
    fun `a newer otp shows again after an older one was handled`() {
        val messages = listOf(otpMessage(id = 20), otpMessage(id = 15, ageMs = 120_000))
        val picked = OtpBannerPolicy.select(messages, handledMessageId = 15, nowMs = now)
        assertThat(picked?.id).isEqualTo(20)
    }

    @Test
    fun `otp older than the age cap is hidden`() {
        val stale = otpMessage(id = 30, ageMs = OtpBannerPolicy.MAX_AGE_MS + 1)
        assertThat(OtpBannerPolicy.select(listOf(stale), handledMessageId = 0, nowMs = now)).isNull()
    }

    @Test
    fun `otp exactly at the age cap still shows`() {
        val edge = otpMessage(id = 31, ageMs = OtpBannerPolicy.MAX_AGE_MS)
        assertThat(OtpBannerPolicy.select(listOf(edge), handledMessageId = 0, nowMs = now)?.id).isEqualTo(31)
    }

    @Test
    fun `messages without an extracted otp are ignored`() {
        val noCode = otpMessage(id = 40, code = null)
        assertThat(OtpBannerPolicy.select(listOf(noCode), handledMessageId = 0, nowMs = now)).isNull()
    }

    @Test
    fun `the newest fresh otp wins among several candidates`() {
        val messages =
            listOf(
                otpMessage(id = 51, ageMs = 300_000),
                otpMessage(id = 52, ageMs = 5_000),
                otpMessage(id = 53, ageMs = 90_000),
            )
        val picked = OtpBannerPolicy.select(messages, handledMessageId = 0, nowMs = now)
        assertThat(picked?.id).isEqualTo(52)
    }

    @Test
    fun `banner tap resolves to the conversation route carrying the message id`() {
        val otp =
            LatestOtp(
                code = "482910",
                senderName = "HDFC Bank",
                timestamp = now,
                messageId = 42,
                threadId = 7,
            )
        assertThat(OtpBannerPolicy.navigationRoute(otp)).isEqualTo("conversation/7?messageId=42")
    }
}
