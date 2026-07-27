package app.clearsms.sms

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SenderRepliabilityTest {
    @Test
    fun `e164 numbers are dialable`() {
        assertThat(SenderRepliability.isDialableNumber("+919876543210")).isTrue()
        assertThat(SenderRepliability.isDialableNumber("+1 (415) 555-0132")).isTrue()
    }

    @Test
    fun `plain 10-digit numbers are dialable`() {
        assertThat(SenderRepliability.isDialableNumber("9876543210")).isTrue()
        assertThat(SenderRepliability.isDialableNumber("98765 43210")).isTrue()
    }

    @Test
    fun `zero-prefixed national numbers are dialable`() {
        assertThat(SenderRepliability.isDialableNumber("09876543210")).isTrue()
    }

    @Test
    fun `alphanumeric TRAI sender ids are not repliable`() {
        assertThat(SenderRepliability.isDialableNumber("VM-HDFCBK")).isFalse()
        assertThat(SenderRepliability.isDialableNumber("AD-AMAZON")).isFalse()
        assertThat(SenderRepliability.isDialableNumber("AX-AMZNIN")).isFalse()
        assertThat(SenderRepliability.isDialableNumber("AX-SWIGGY-S")).isFalse()
    }

    @Test
    fun `short codes are not repliable`() {
        assertThat(SenderRepliability.isDialableNumber("56767")).isFalse()
        assertThat(SenderRepliability.isDialableNumber("777777")).isFalse()
    }

    @Test
    fun `empty and blank addresses are not repliable`() {
        assertThat(SenderRepliability.isDialableNumber("")).isFalse()
        assertThat(SenderRepliability.isDialableNumber("   ")).isFalse()
    }

    @Test
    fun `numbers beyond e164 length are not repliable`() {
        assertThat(SenderRepliability.isDialableNumber("1234567890123456")).isFalse()
    }

    @Test
    fun `isRepliable falls back to the core verdict off-device`() {
        // On the plain JVM the framework PhoneNumberUtils stubs throw; the
        // pure core must still decide.
        assertThat(SenderRepliability.isRepliable("+919876543210")).isTrue()
        assertThat(SenderRepliability.isRepliable("AD-AMAZON")).isFalse()
    }
}
