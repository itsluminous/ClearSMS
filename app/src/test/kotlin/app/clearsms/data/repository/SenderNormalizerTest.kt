package app.clearsms.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Issue #42: the same person appeared as two inbox threads because the
 * thread key was `digits.takeLast(10)`, which assumes ten-digit national
 * numbers. The key is now the region-aware national number; alphanumeric
 * ids and short codes keep the rule they always had. Every number below is
 * synthetic.
 */
class SenderNormalizerTest {
    @Before
    fun setUp() {
        SenderNormalizer.defaultRegion = null
    }

    @After
    fun tearDown() {
        SenderNormalizer.defaultRegion = null
    }

    private fun key(
        sender: String,
        region: String?,
    ) = SenderNormalizer.normalize(sender, region)

    @Test
    fun `polish nine-digit number - international and local forms share one key`() {
        // The reported bug: 48601234567 -> last ten = 8601234567 vs 601234567.
        assertThat(key("+48 601 234 567", "PL")).isEqualTo("601234567")
        assertThat(key("601 234 567", "PL")).isEqualTo("601234567")
        assertThat(key("+48601234567", "PL")).isEqualTo("601234567")
        assertThat(key("0048601234567", "PL")).isEqualTo("601234567")
        // Poland has no trunk prefix: a leading digit is never stripped.
        assertThat(key("601234567", "PL")).isEqualTo("601234567")
    }

    @Test
    fun `indian ten-digit numbers keep working exactly as before`() {
        for (variant in listOf("+91 98765 43210", "+919876543210", "9876543210", "09876543210", "919876543210")) {
            assertThat(key(variant, "IN")).isEqualTo("9876543210")
        }
        // A valid ten-digit number that happens to start with the country
        // code's digits is NOT cut.
        assertThat(key("9198765432", "IN")).isEqualTo("9198765432")
        // Landline with STD code: trunk 0 stripped, the code kept.
        assertThat(key("01123456789", "IN")).isEqualTo("1123456789")
        assertThat(key("+911123456789", "IN")).isEqualTo("1123456789")
    }

    @Test
    fun `US and Canada - plus one, bare eleven digits and ten digits agree`() {
        for (variant in listOf("+1 415 555 2671", "+14155552671", "14155552671", "4155552671", "(415) 555-2671")) {
            assertThat(key(variant, "US")).isEqualTo("4155552671")
            assertThat(key(variant, "CA")).isEqualTo("4155552671")
        }
    }

    @Test
    fun `UK - with and without the trunk zero`() {
        for (variant in listOf("+44 7911 123456", "+447911123456", "07911 123456", "07911123456", "7911123456")) {
            assertThat(key(variant, "GB")).isEqualTo("7911123456")
        }
    }

    @Test
    fun `german variable-length numbers reduce to the same national number`() {
        assertThat(key("+49 151 12345678", "DE")).isEqualTo("15112345678")
        assertThat(key("0151 12345678", "DE")).isEqualTo("15112345678")
        // A shorter landline: still one key across the forms.
        assertThat(key("+49 30 901820", "DE")).isEqualTo("30901820")
        assertThat(key("030 901820", "DE")).isEqualTo("30901820")
    }

    @Test
    fun `italy - the leading zero is part of the number and survives`() {
        assertThat(key("+39 06 1234567", "IT")).isEqualTo("061234567")
        assertThat(key("06 1234567", "IT")).isEqualTo("061234567")
    }

    @Test
    fun `unknown region is conservative - international forms reduce, national forms keep every digit`() {
        assertThat(key("+48 601 234 567", null)).isEqualTo("601234567")
        assertThat(key("601234567", null)).isEqualTo("601234567")
        // A trunk prefix we cannot vouch for is never stripped ...
        assertThat(key("07911123456", null)).isEqualTo("07911123456")
        assertThat(key("+447911123456", null)).isEqualTo("7911123456")
        // ... and an unlisted region behaves the same way.
        assertThat(key("07911123456", "ZZ")).isEqualTo("07911123456")
    }

    @Test
    fun `the process-wide region is what the one-argument form uses`() {
        SenderNormalizer.defaultRegion = "PL"
        assertThat(SenderNormalizer.normalize("601 234 567")).isEqualTo("601234567")
        assertThat(SenderNormalizer.normalize("+48 601 234 567")).isEqualTo("601234567")
        SenderNormalizer.defaultRegion = "GB"
        assertThat(SenderNormalizer.normalize("07911 123456")).isEqualTo("7911123456")
    }

    @Test
    fun `alphanumeric sender ids keep the TRAI route-variant rule and never merge with numbers`() {
        for (region in listOf(null, "IN", "PL")) {
            assertThat(key("VM-HDFCBK-S", region)).isEqualTo("HDFCBK")
            assertThat(key("AD-HDFCBK", region)).isEqualTo("HDFCBK")
            assertThat(key("hdfcbk", region)).isEqualTo("HDFCBK")
            assertThat(key("JD-AMAZON", region)).isEqualTo("AMAZON")
        }
        assertThat(SenderNormalizer.isPhoneNumber("VM-HDFCBK-S")).isFalse()
    }

    @Test
    fun `short codes are their own senders - not phone numbers, not merged`() {
        for (region in listOf(null, "IN", "PL", "US")) {
            assertThat(key("56767", region)).isEqualTo("56767")
            assertThat(key("57575", region)).isEqualTo("57575")
            assertThat(key("121", region)).isEqualTo("121")
        }
        assertThat(SenderNormalizer.isPhoneNumber("56767")).isFalse()
        // A phone number ending in a short code's digits is a different sender.
        assertThat(key("+919856767", "IN")).isNotEqualTo(key("56767", "IN"))
        assertThat(key("9876556767", "IN")).isNotEqualTo("56767")
        // And alphanumeric vs short code never collide either.
        assertThat(key("VM-HDFCBK", "IN")).isNotEqualTo(key("56767", "IN"))
    }

    @Test
    fun `two different people never share a key`() {
        assertThat(key("+919876543210", "IN")).isNotEqualTo(key("+919876543211", "IN"))
        assertThat(key("+48601234567", "PL")).isNotEqualTo(key("+48601234568", "PL"))
        assertThat(key("+14155552671", "US")).isNotEqualTo(key("+12125552671", "US"))
    }

    @Test
    fun `blocked and muted entries stored under the OLD ten-digit key still match their sender`() {
        SenderNormalizer.defaultRegion = "PL"
        // Blocking "+48 601 234 567" before #42 stored "8601234567".
        val oldEntries = setOf("8601234567")
        assertThat(SenderNormalizer.matchesAny(oldEntries, "+48601234567")).isTrue()
        assertThat(SenderNormalizer.matchesAny(oldEntries, "+48 601 234 567")).isTrue()
        // Not a regression, not a fix either: the old key never matched the
        // local form and still does not - only a NEW entry does (below).
        assertThat(SenderNormalizer.matchesAny(oldEntries, "601234567")).isFalse()
        assertThat(SenderNormalizer.matchesAny(oldEntries, "+48601234568")).isFalse()

        SenderNormalizer.defaultRegion = "DE"
        // Blocking "+49 151 12345678" before #42 stored "5112345678".
        assertThat(SenderNormalizer.matchesAny(setOf("5112345678"), "+4915112345678")).isTrue()
        assertThat(SenderNormalizer.matchesAny(setOf("5112345678"), "015112345678")).isTrue()

        SenderNormalizer.defaultRegion = "IN"
        // Indian entries: old key == new key, nothing moves.
        assertThat(SenderNormalizer.matchesAny(setOf("9876543210"), "+919876543210")).isTrue()
        assertThat(SenderNormalizer.matchesAny(setOf("9876543210"), "09876543210")).isTrue()
        assertThat(SenderNormalizer.matchesAny(setOf("9876543210"), "+919876543211")).isFalse()
    }

    @Test
    fun `an entry stored under the NEW key matches the variants the old key missed - the bug fix`() {
        SenderNormalizer.defaultRegion = "PL"
        val entries = setOf(SenderNormalizer.normalize("+48 601 234 567"))
        assertThat(entries).containsExactly("601234567")
        assertThat(SenderNormalizer.matchesAny(entries, "+48601234567")).isTrue()
        assertThat(SenderNormalizer.matchesAny(entries, "601234567")).isTrue()
        assertThat(SenderNormalizer.matchesAny(entries, "601 234 567")).isTrue()
        assertThat(SenderNormalizer.matchesAny(entries, "+48601234568")).isFalse()
    }

    @Test
    fun `legacy matching is for phone numbers only - never lets a short code or id through`() {
        SenderNormalizer.defaultRegion = "IN"
        assertThat(SenderNormalizer.matchesAny(setOf("9876556767"), "56767")).isFalse()
        assertThat(SenderNormalizer.matchesAny(setOf("56767"), "9876556767")).isFalse()
        assertThat(SenderNormalizer.matchesAny(setOf("HDFCBK"), "VM-HDFCBK-S")).isTrue()
        assertThat(SenderNormalizer.matchesAny(setOf("HDFCBK"), "HDFC")).isFalse()
        assertThat(SenderNormalizer.matchesAny(setOf(""), "")).isFalse()
    }

    @Test
    fun `sameSender is symmetric and is what unblock and unmute remove by`() {
        SenderNormalizer.defaultRegion = "PL"
        assertThat(SenderNormalizer.sameSender("8601234567", "+48601234567")).isTrue()
        assertThat(SenderNormalizer.sameSender("+48601234567", "8601234567")).isTrue()
        assertThat(SenderNormalizer.sameSender("601234567", "+48601234567")).isTrue()
        assertThat(SenderNormalizer.sameSender("VM-JIOPAY", "JIOPAY")).isTrue()
        assertThat(SenderNormalizer.sameSender("601234567", "601234568")).isFalse()
        assertThat(SenderNormalizer.sameSender("", "")).isFalse()
    }

    @Test
    fun `the precomputed matcher agrees with matchesAny`() {
        SenderNormalizer.defaultRegion = "PL"
        val entries = setOf("8601234567", "HDFCBK", "601234599")
        val matcher = SenderNormalizer.matcher(entries)
        for (sender in listOf("+48601234567", "601234567", "601234599", "+48601234599", "VM-HDFCBK-S", "56767", "AD-OTHER", "")) {
            assertThat(matcher(sender)).isEqualTo(SenderNormalizer.matchesAny(entries, sender))
        }
    }

    @Test
    fun `loose number equality - variants pass, different people and short codes do not`() {
        assertThat(PhoneNumberKey.looselySame("601234567", "8601234567")).isTrue()
        assertThat(PhoneNumberKey.looselySame("07911123456", "7911123456")).isTrue()
        assertThat(PhoneNumberKey.looselySame("4155552671", "2125552671")).isFalse()
        assertThat(PhoneNumberKey.looselySame("56767", "9876556767")).isFalse()
        assertThat(PhoneNumberKey.looselySame("HDFCBK", "HDFCBK")).isFalse()
        assertThat(PhoneNumberKey.looselySame("", "1234567")).isFalse()
    }

    @Test
    fun `country codes are recognised by the E164 length rule`() {
        assertThat(PhoneNumberKey.countryCodeOf("14155552671")).isEqualTo("1")
        assertThat(PhoneNumberKey.countryCodeOf("79161234567")).isEqualTo("7")
        assertThat(PhoneNumberKey.countryCodeOf("48601234567")).isEqualTo("48")
        assertThat(PhoneNumberKey.countryCodeOf("919876543210")).isEqualTo("91")
        assertThat(PhoneNumberKey.countryCodeOf("353871234567")).isEqualTo("353")
        assertThat(PhoneNumberKey.countryCodeOf("971501234567")).isEqualTo("971")
    }
}
