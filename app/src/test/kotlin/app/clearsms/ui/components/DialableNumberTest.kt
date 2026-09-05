package app.clearsms.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The shared dialability rule, extracted from [BodyLinkFinder] for the
 * conversation top bar. These tests pin the extraction: the guarantees the
 * body-link path relied on (Indian 10-digit mobiles, +CC 8-15 digits, 1800
 * toll-free - and NOTHING else) must survive unchanged.
 */
class DialableNumberTest {
    @Test
    fun `bare indian mobile is dialable`() {
        assertThat(DialableNumber.of("9876543210")).isEqualTo("9876543210")
        assertThat(DialableNumber.of("6123456789")).isEqualTo("6123456789")
    }

    @Test
    fun `ten digits outside the 6-9 mobile range are not dialable`() {
        // The shape of a PNR or an account fragment - never a mobile.
        assertThat(DialableNumber.of("5876543210")).isNull()
        assertThat(DialableNumber.of("1234567890")).isNull()
    }

    @Test
    fun `country code form keeps its plus and separators are tolerated`() {
        assertThat(DialableNumber.of("+919876543210")).isEqualTo("+919876543210")
        assertThat(DialableNumber.of("+91 98765 43210")).isEqualTo("+919876543210")
        assertThat(DialableNumber.of("98765-43210")).isEqualTo("9876543210")
    }

    @Test
    fun `country code length is bounded to E164`() {
        assertThat(DialableNumber.of("+1234567")).isNull() // 7 digits: too short
        assertThat(DialableNumber.of("+12345678")).isEqualTo("+12345678")
        assertThat(DialableNumber.of("+123456789012345")).isEqualTo("+123456789012345")
        assertThat(DialableNumber.of("+1234567890123456")).isNull() // 16: too long
    }

    @Test
    fun `toll free 1800 line is dialable`() {
        assertThat(DialableNumber.of("18001234567")).isEqualTo("18001234567")
    }

    @Test
    fun `short codes are never dialable`() {
        // Helplines and TRAI short codes (issue #5's 139 case): the dialer
        // must not open on them from the top bar either.
        assertThat(DialableNumber.of("139")).isNull()
        assertThat(DialableNumber.of("1930")).isNull()
        assertThat(DialableNumber.of("56767")).isNull()
    }

    @Test
    fun `alphanumeric sender ids are never dialable`() {
        assertThat(DialableNumber.of("HDFCBK")).isNull()
        assertThat(DialableNumber.of("VM-HDFCBK")).isNull()
        assertThat(DialableNumber.of("AX-AMZNIN")).isNull()
    }

    @Test
    fun `long digit runs are not numbers to call`() {
        assertThat(DialableNumber.of("12345678901")).isNull() // 11-digit txn id
        assertThat(DialableNumber.of("1234567890123456")).isNull() // 16-digit card
    }

    @Test
    fun `blank input is not dialable`() {
        assertThat(DialableNumber.of("")).isNull()
        assertThat(DialableNumber.of("+")).isNull()
    }
}
