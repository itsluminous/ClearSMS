package app.clearsms.sms

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ContactCacheKeyTest {
    @Test
    fun `e164 national and bare forms of the same number share one cache key`() {
        val e164 = ContactsSource.cacheKeyFor("+919876543210")
        val national = ContactsSource.cacheKeyFor("09876543210")
        val bare = ContactsSource.cacheKeyFor("9876543210")
        assertThat(e164).isEqualTo("9876543210")
        assertThat(national).isEqualTo(e164)
        assertThat(bare).isEqualTo(e164)
    }

    @Test
    fun `formatting characters do not change the key`() {
        assertThat(ContactsSource.cacheKeyFor("+91 98765 43210"))
            .isEqualTo(ContactsSource.cacheKeyFor("9876543210"))
        assertThat(ContactsSource.cacheKeyFor("(987) 654-3210"))
            .isEqualTo(ContactsSource.cacheKeyFor("9876543210"))
    }

    @Test
    fun `different numbers produce different keys`() {
        assertThat(ContactsSource.cacheKeyFor("+919876543210"))
            .isNotEqualTo(ContactsSource.cacheKeyFor("+919876543211"))
    }

    @Test
    fun `short codes keep their full digit string`() {
        assertThat(ContactsSource.cacheKeyFor("56767")).isEqualTo("56767")
    }

    @Test
    fun `a short code never shares a cache entry with a long number ending in its digits`() {
        // GitHub #75: 80122 vs strangers whose numbers merely end in 80122.
        val shortCode = ContactsSource.cacheKeyFor("80122")
        assertThat(shortCode).isNotEqualTo(ContactsSource.cacheKeyFor("+4917080122"))
        assertThat(shortCode).isNotEqualTo(ContactsSource.cacheKeyFor("5550080122"))
        assertThat(shortCode).isNotEqualTo(ContactsSource.cacheKeyFor("+15550080122"))
        assertThat(shortCode).isNotEqualTo(ContactsSource.cacheKeyFor("017080122"))
        // Nor with a seven-digit local number sharing the suffix.
        assertThat(shortCode).isNotEqualTo(ContactsSource.cacheKeyFor("0080122"))
        // Only the same code, however formatted, shares the entry.
        assertThat(shortCode).isEqualTo(ContactsSource.cacheKeyFor("80 122"))
    }

    @Test
    fun `a short-code address accepts only a row saved as exactly that code`() {
        assertThat(ContactsSource.acceptsMatch("80122", "80122")).isTrue()
        assertThat(ContactsSource.acceptsMatch("80122", "80 122")).isTrue()
        // A stranger whose number ends in the code must not lend it their name.
        assertThat(ContactsSource.acceptsMatch("80122", "+4917080122")).isFalse()
        assertThat(ContactsSource.acceptsMatch("80122", "5550080122")).isFalse()
        assertThat(ContactsSource.acceptsMatch("80122", "0080122")).isFalse()
        // A row we cannot verify is refused for a short code.
        assertThat(ContactsSource.acceptsMatch("80122", null)).isFalse()
    }

    @Test
    fun `a long number never borrows the name saved for a short code`() {
        assertThat(ContactsSource.acceptsMatch("+4917080122", "80122")).isFalse()
        assertThat(ContactsSource.acceptsMatch("5550080122", "80122")).isFalse()
        assertThat(ContactsSource.acceptsMatch("017080122", "80122")).isFalse()
    }

    @Test
    fun `subscriber numbers keep trusting the provider's own matching`() {
        // E.164 / national / bare forms of one number are the provider's job.
        assertThat(ContactsSource.acceptsMatch("+919876543210", "09876543210")).isTrue()
        assertThat(ContactsSource.acceptsMatch("9876543210", "+91 98765 43210")).isTrue()
        assertThat(ContactsSource.acceptsMatch("+919876543210", null)).isTrue()
    }
}
