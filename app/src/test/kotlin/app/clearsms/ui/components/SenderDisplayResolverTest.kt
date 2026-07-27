package app.clearsms.ui.components

import app.clearsms.sms.ContactInfo
import app.clearsms.sms.ContactsSource
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SenderDisplayResolverTest {
    /** Contacts keyed by normalized number, like the provider's PhoneLookup. */
    private val savedContacts =
        mapOf(
            ContactsSource.cacheKeyFor("9876543210") to ContactInfo("Asha Rao", photoUri = "content://photo/7"),
        )

    private val contactLookup: (String) -> ContactInfo? = { address ->
        savedContacts[ContactsSource.cacheKeyFor(address)]
    }

    private val directory: (String) -> String? = { sender ->
        if (sender.contains("HDFCBK")) "HDFC Bank" else null
    }

    @Test
    fun `saved contact resolves across e164 national and bare 10-digit forms`() {
        for (variant in listOf("+919876543210", "09876543210", "9876543210", "+91 98765 43210")) {
            val display = resolveSenderDisplay(variant, contactLookup, directory)
            assertThat(display.name).isEqualTo("Asha Rao")
            assertThat(display.photoUri).isEqualTo("content://photo/7")
            assertThat(display.isContact).isTrue()
            assertThat(display.isKnownSender).isFalse()
        }
    }

    @Test
    fun `directory name is used when the sender is not a contact`() {
        val display = resolveSenderDisplay("VM-HDFCBK", contactLookup, directory)
        assertThat(display.name).isEqualTo("HDFC Bank")
        assertThat(display.isContact).isFalse()
        assertThat(display.isKnownSender).isTrue()
        assertThat(display.photoUri).isNull()
    }

    @Test
    fun `unknown sender falls back to the raw address`() {
        val display = resolveSenderDisplay("+911234567890", contactLookup, directory)
        assertThat(display.name).isEqualTo("+911234567890")
        assertThat(display.isContact).isFalse()
        assertThat(display.isKnownSender).isFalse()
    }

    @Test
    fun `contact wins over a directory match`() {
        val greedyDirectory: (String) -> String? = { "Directory Name" }
        val display = resolveSenderDisplay("09876543210", contactLookup, greedyDirectory)
        assertThat(display.name).isEqualTo("Asha Rao")
        assertThat(display.isContact).isTrue()
    }
}
