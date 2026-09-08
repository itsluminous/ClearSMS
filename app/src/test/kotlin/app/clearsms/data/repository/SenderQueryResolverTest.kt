package app.clearsms.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The name-to-address join behind search-by-sender: contact names (via a
 * fake contact source - never a real provider), resolved sender names, and
 * raw sender IDs all resolve to the exact DB addresses the SQL then matches.
 */
class SenderQueryResolverTest {
    private val noContacts: (String) -> List<String> = { emptyList() }
    private val noNames: (String) -> String? = { null }

    @Test
    fun `contact name resolves to the DB address across number formats`() {
        // DB stores E.164; the contact's number is saved in national format.
        val matches =
            SenderQueryResolver.resolve(
                query = "asha",
                senders = listOf("+911234512345", "VM-HDFCBK"),
                contactNumbersMatching = { q ->
                    if (q == "asha") listOf("01234 512345") else emptyList()
                },
                resolvedName = noNames,
            )

        assertThat(matches.addresses).containsExactly("+911234512345")
        assertThat(matches.reasons["+911234512345"]).isEqualTo(SenderMatchReason.CONTACT_NAME)
    }

    @Test
    fun `resolved sender name matches directory brands`() {
        val matches =
            SenderQueryResolver.resolve(
                query = "sample bank",
                senders = listOf("VM-SMPBNK", "AX-OTHER"),
                contactNumbersMatching = noContacts,
                resolvedName = { sender -> if (sender == "VM-SMPBNK") "Sample Bank" else null },
            )

        assertThat(matches.addresses).containsExactly("VM-SMPBNK")
        assertThat(matches.reasons["VM-SMPBNK"]).isEqualTo(SenderMatchReason.SENDER_NAME)
    }

    @Test
    fun `raw sender id matches when nothing resolves it`() {
        val matches =
            SenderQueryResolver.resolve(
                query = "smpbnk",
                senders = listOf("VM-SMPBNK"),
                contactNumbersMatching = noContacts,
                resolvedName = noNames,
            )

        assertThat(matches.reasons["VM-SMPBNK"]).isEqualTo(SenderMatchReason.SENDER_ID)
    }

    @Test
    fun `no match yields the empty set`() {
        val matches =
            SenderQueryResolver.resolve(
                query = "electricity",
                senders = listOf("VM-SMPBNK", "+911234512345"),
                contactNumbersMatching = noContacts,
                resolvedName = { "Sample Bank" },
            )

        assertThat(matches.addresses).isEmpty()
    }

    @Test
    fun `matching is case-insensitive and diacritic-insensitive`() {
        val matches =
            SenderQueryResolver.resolve(
                query = "CAFE",
                senders = listOf("VM-CAFXYZ"),
                contactNumbersMatching = noContacts,
                resolvedName = { "Café Rewards" },
            )

        assertThat(matches.addresses).containsExactly("VM-CAFXYZ")
    }

    @Test
    fun `multi-token queries are order-free ANDs of word prefixes`() {
        val resolve = { query: String ->
            SenderQueryResolver
                .resolve(
                    query = query,
                    senders = listOf("VM-SMPBNK"),
                    contactNumbersMatching = noContacts,
                    resolvedName = { "Sample Bank" },
                ).addresses
        }

        assertThat(resolve("bank sam")).containsExactly("VM-SMPBNK")
        assertThat(resolve("sample credit")).isEmpty()
        // Prefix, not infix: mirrors the body FTS semantics.
        assertThat(resolve("ample")).isEmpty()
    }

    @Test
    fun `contact source is never consulted when no sender is a phone number`() {
        val matches =
            SenderQueryResolver.resolve(
                query = "asha",
                senders = listOf("VM-SMPBNK", "AX-OTHER"),
                contactNumbersMatching = { error("provider query for a corpus without phone senders") },
                resolvedName = noNames,
            )

        assertThat(matches.addresses).isEmpty()
    }

    @Test
    fun `resolved addresses are capped`() {
        val senders = (0 until 500).map { "VM-BNK$it" }
        val matches =
            SenderQueryResolver.resolve(
                query = "bnk",
                senders = senders,
                contactNumbersMatching = noContacts,
                resolvedName = noNames,
            )

        assertThat(matches.addresses).hasSize(SenderQueryResolver.MAX_MATCHES)
    }

    @Test
    fun `textMatches mirrors token-prefix semantics for bodies`() {
        assertThat(SenderQueryResolver.textMatches("salar", "Salary credited to account")).isTrue()
        assertThat(SenderQueryResolver.textMatches("electricity", "Salary credited")).isFalse()
        assertThat(SenderQueryResolver.textMatches("", "anything")).isFalse()
    }
}
