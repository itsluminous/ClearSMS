package app.clearsms.sms

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ContactLookupNormalizationTest {
    @Test
    fun `plain 10 digit number looks like a phone number`() {
        assertThat(ContactLookupImpl.looksLikePhoneNumber("9876543210")).isTrue()
    }

    @Test
    fun `international format with plus and spaces is accepted`() {
        assertThat(ContactLookupImpl.looksLikePhoneNumber("+91 98765 43210")).isTrue()
    }

    @Test
    fun `formatted number with dashes and parentheses is accepted`() {
        assertThat(ContactLookupImpl.looksLikePhoneNumber("(555) 123-4567")).isTrue()
    }

    @Test
    fun `alphanumeric sender ids are rejected`() {
        assertThat(ContactLookupImpl.looksLikePhoneNumber("VM-HDFCBK")).isFalse()
        assertThat(ContactLookupImpl.looksLikePhoneNumber("AX-AMAZON-S")).isFalse()
        assertThat(ContactLookupImpl.looksLikePhoneNumber("JD-DELHIV")).isFalse()
    }

    @Test
    fun `short codes below minimum digits are rejected`() {
        assertThat(ContactLookupImpl.looksLikePhoneNumber("1909")).isFalse()
    }

    @Test
    fun `blank and empty addresses are rejected`() {
        assertThat(ContactLookupImpl.looksLikePhoneNumber("")).isFalse()
        assertThat(ContactLookupImpl.looksLikePhoneNumber("   ")).isFalse()
    }
}
