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
}
