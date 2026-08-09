package app.clearsms.notification

import app.clearsms.sms.ContactInfo
import app.clearsms.ui.components.Brand
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The notifier resolves senders through the same chain the UI uses:
 * contact → sender-ID directory → curated brand table → raw address -
 * degrading (never crashing) when a tier throws, e.g. READ_CONTACTS denied.
 */
class NotificationSenderResolutionTest {
    private val brand =
        Brand(key = "hdfc", name = "HDFC Bank", color = "#004C8F", monogram = "H", senders = listOf("HDFCBK"))

    @Test
    fun `contact name and photo win over directory and brand`() {
        val resolved =
            resolveNotificationSender(
                sender = "+919876543210",
                contactLookup = { ContactInfo(name = "Asha Rao", photoUri = "content://photo/1") },
                directoryLookup = { "Directory Name" },
                brandLookup = { brand },
            )
        assertThat(resolved.name).isEqualTo("Asha Rao")
        assertThat(resolved.photoUri).isEqualTo("content://photo/1")
        assertThat(resolved.isContact).isTrue()
        assertThat(resolved.monogram).isEqualTo("AR")
    }

    @Test
    fun `directory name is used when there is no contact`() {
        val resolved =
            resolveNotificationSender(
                sender = "VM-HDFCBK",
                contactLookup = { null },
                directoryLookup = { "HDFC Bank" },
                brandLookup = { null },
            )
        assertThat(resolved.name).isEqualTo("HDFC Bank")
        assertThat(resolved.isContact).isFalse()
    }

    @Test
    fun `brand table is the fallback after the directory`() {
        val resolved =
            resolveNotificationSender(
                sender = "VM-HDFCBK",
                contactLookup = { null },
                directoryLookup = { null },
                brandLookup = { brand },
            )
        assertThat(resolved.name).isEqualTo("HDFC Bank")
        assertThat(resolved.monogram).isEqualTo("H")
        assertThat(resolved.colorArgb).isEqualTo(0xFF004C8F.toInt())
    }

    @Test
    fun `raw address is kept when nothing resolves`() {
        val resolved =
            resolveNotificationSender(
                sender = "AX-UNKNWN",
                contactLookup = { null },
                directoryLookup = { null },
                brandLookup = { null },
            )
        assertThat(resolved.name).isEqualTo("AX-UNKNWN")
        assertThat(resolved.photoUri).isNull()
        assertThat(resolved.colorArgb).isNull()
    }

    @Test
    fun `a throwing contact lookup degrades to the next tier instead of crashing`() {
        val resolved =
            resolveNotificationSender(
                sender = "VM-HDFCBK",
                contactLookup = { throw SecurityException("READ_CONTACTS denied") },
                directoryLookup = { "HDFC Bank" },
                brandLookup = { throw IllegalStateException("asset unreadable") },
            )
        assertThat(resolved.name).isEqualTo("HDFC Bank")
    }

    @Test
    fun `every tier throwing still yields the raw address`() {
        val resolved =
            resolveNotificationSender(
                sender = "9876543210",
                contactLookup = { throw SecurityException() },
                directoryLookup = { throw IllegalStateException() },
                brandLookup = { throw IllegalStateException() },
            )
        assertThat(resolved.name).isEqualTo("9876543210")
    }

    @Test
    fun `directory name gets brand tile facts when the brand also matches`() {
        val resolved =
            resolveNotificationSender(
                sender = "VM-HDFCBK",
                contactLookup = { null },
                directoryLookup = { "HDFC Bank" },
                brandLookup = { brand },
            )
        assertThat(resolved.name).isEqualTo("HDFC Bank")
        assertThat(resolved.monogram).isEqualTo("H")
        assertThat(resolved.colorArgb).isNotNull()
    }

    @Test
    fun `malformed brand color is ignored`() {
        assertThat(parseHexColor("#GGGGGG")).isNull()
        assertThat(parseHexColor("#123")).isNull()
        assertThat(parseHexColor("#004C8F")).isEqualTo(0xFF004C8F.toInt())
    }
}
