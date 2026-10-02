package app.clearsms.sms

import app.clearsms.sms.SenderRepliability.Repliability
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Who can be replied to (GitHub #75). Two facts the predicate must keep
 * apart: an alphanumeric sender id is UNADDRESSABLE because the platform
 * drops letters when it encodes an SMS destination (nothing to do with the
 * sender "refusing"); a numeric short code is a real destination that is
 * often two-way, so it is addressable and only its acceptance is unknown.
 */
class SenderRepliabilityTest {
    @Test
    fun `e164 numbers are subscriber numbers`() {
        assertThat(SenderRepliability.classify("+919876543210")).isEqualTo(Repliability.NUMBER)
        assertThat(SenderRepliability.classify("+1 (415) 555-0132")).isEqualTo(Repliability.NUMBER)
        assertThat(SenderRepliability.classify("+49 170 1234567")).isEqualTo(Repliability.NUMBER)
        assertThat(SenderRepliability.isAddressable("+49 170 1234567")).isTrue()
    }

    @Test
    fun `plain and zero-prefixed national numbers are subscriber numbers`() {
        assertThat(SenderRepliability.classify("9876543210")).isEqualTo(Repliability.NUMBER)
        assertThat(SenderRepliability.classify("98765 43210")).isEqualTo(Repliability.NUMBER)
        assertThat(SenderRepliability.classify("09876543210")).isEqualTo(Repliability.NUMBER)
        // Exactly the number floor.
        assertThat(SenderRepliability.classify("1234567")).isEqualTo(Repliability.NUMBER)
    }

    @Test
    fun `numeric short codes are addressable - the reporter's carrier code first`() {
        // Reply WEITER to 80122 re-enables data on a German plan; the old
        // 7-digit floor made that impossible. Also a UK/US 5-digit code, an
        // Indian 5-digit OTP sender and Indian Railways' 3-digit 139.
        listOf("80122", "56767", "22000", "139", "777777").forEach { code ->
            assertWithMessage(code).that(SenderRepliability.classify(code)).isEqualTo(Repliability.SHORT_CODE)
            assertWithMessage(code).that(SenderRepliability.isAddressable(code)).isTrue()
        }
        // Spaces and dashes are formatting, as for full numbers.
        assertThat(SenderRepliability.classify("801 22")).isEqualTo(Repliability.SHORT_CODE)
        assertThat(SenderRepliability.classify("80-122")).isEqualTo(Repliability.SHORT_CODE)
    }

    @Test
    fun `alphanumeric sender ids cannot be addressed`() {
        // The platform's extractNetworkPortion keeps only 0-9 * # +, so a
        // name encodes to an empty (or, worse, a mangled) destination.
        listOf("VM-HDFCBK", "AD-AMAZON", "AX-AMZNIN", "AX-SWIGGY-S", "O2", "Amazon").forEach { id ->
            assertWithMessage(id).that(SenderRepliability.classify(id)).isEqualTo(Repliability.UNADDRESSABLE_NAME)
            assertWithMessage(id).that(SenderRepliability.isAddressable(id)).isFalse()
        }
    }

    @Test
    fun `one and two digit addresses are not destinations`() {
        listOf("1", "7", "22", "+1").forEach { address ->
            assertWithMessage(address).that(SenderRepliability.classify(address)).isEqualTo(Repliability.INVALID)
        }
        assertThat(SenderRepliability.SHORT_CODE_MIN_DIGITS).isEqualTo(3)
        assertThat(SenderRepliability.classify("100")).isEqualTo(Repliability.SHORT_CODE)
    }

    @Test
    fun `empty, blank and symbol-only addresses are not destinations`() {
        listOf("", "   ", "+", "-", "( )", "*#").forEach { address ->
            assertWithMessage("'$address'").that(SenderRepliability.classify(address)).isEqualTo(Repliability.INVALID)
            assertWithMessage("'$address'").that(SenderRepliability.isAddressable(address)).isFalse()
        }
    }

    @Test
    fun `numbers beyond e164 length are not destinations`() {
        assertThat(SenderRepliability.classify("1234567890123456")).isEqualTo(Repliability.INVALID)
        assertThat(SenderRepliability.classify("+1234567890123456")).isEqualTo(Repliability.INVALID)
        // Exactly the ceiling is still a number.
        assertThat(SenderRepliability.classify("123456789012345")).isEqualTo(Repliability.NUMBER)
        assertThat(SenderRepliability.E164_MAX_DIGITS).isEqualTo(15)
    }

    @Test
    fun `only numbers and short codes are addressable`() {
        assertThat(Repliability.entries.filter { it.addressable })
            .containsExactly(Repliability.NUMBER, Repliability.SHORT_CODE)
    }

    @Test
    fun `isRepliable falls back to the core verdict off-device`() {
        // On the plain JVM the framework PhoneNumberUtils stubs throw; the
        // pure core must still decide - for short codes too.
        assertThat(SenderRepliability.isRepliable("+919876543210")).isTrue()
        assertThat(SenderRepliability.isRepliable("80122")).isTrue()
        assertThat(SenderRepliability.isRepliable("AD-AMAZON")).isFalse()
        assertThat(SenderRepliability.isRepliable("")).isFalse()
        assertThat(SenderRepliability.classifyOnDevice("80122")).isEqualTo(Repliability.SHORT_CODE)
        assertThat(SenderRepliability.classifyOnDevice("VM-HDFCBK")).isEqualTo(Repliability.UNADDRESSABLE_NAME)
    }
}
