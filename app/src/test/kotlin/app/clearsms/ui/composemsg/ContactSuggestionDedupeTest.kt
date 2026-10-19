package app.clearsms.ui.composemsg

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Issue #127: the compose screen showed four rows for a contact whose
 * phonebook entry has two numbers. The contacts provider returns one row per
 * phone DATA row, and a contact synced through several accounts carries one
 * per account, each in that adapter's formatting; dedupe keyed on the raw
 * text kept every formatting variant. These tests pin the identity the
 * dedupe uses (formatting-blind, otherwise literal), the survivor rule, and
 * the over-merges it must NOT perform. Every number here is synthetic.
 */
class ContactSuggestionDedupeTest {
    private val photo = "content://com.android.contacts/contacts/7/photo"

    /** The four rows from the report, in the order the screenshot shows them. */
    private val report =
        listOf(
            ContactSuggestion("David", "+61 414 708 088"),
            ContactSuggestion("David", "085-270-609558"),
            ContactSuggestion("David", "0859-4188-1970"),
            ContactSuggestion("David", "085941881970"),
        )

    @Test
    fun `the reported four rows become three - the two copies of one number collapse`() {
        val deduped = dedupeSuggestions(report)
        assertThat(deduped.map { it.number }).containsExactly("+61 414 708 088", "085-270-609558", "0859-4188-1970").inOrder()
        assertThat(deduped.map { it.listKey }).containsNoDuplicates()
    }

    @Test
    fun `the Australian number is not provably a duplicate and survives`() {
        // The phonebook's edit screen shows one raw contact; the aggregate may
        // legitimately carry this number. Nothing else in the list shares its
        // characters, so nothing may absorb it.
        assertThat(dedupeSuggestions(report).map { it.number }).contains("+61 414 708 088")
    }

    @Test
    fun `the readable copy survives over the unbroken digit run, whichever came first`() {
        val readableFirst = listOf(ContactSuggestion("David", "0859-4188-1970"), ContactSuggestion("David", "085941881970"))
        val uglyFirst = readableFirst.reversed()
        assertThat(dedupeSuggestions(readableFirst).single().number).isEqualTo("0859-4188-1970")
        assertThat(dedupeSuggestions(uglyFirst).single().number).isEqualTo("0859-4188-1970")
    }

    @Test
    fun `more grouping beats less, a leading plus breaks the tie, provider order breaks the rest`() {
        val grouped = ContactSuggestion("Ana", "+48 601 234 567")
        val lessGrouped = ContactSuggestion("Ana", "+48 601234567")
        val noPlus = ContactSuggestion("Ana", "48 601 234 567")
        assertThat(dedupeSuggestions(listOf(lessGrouped, noPlus, grouped)).single()).isEqualTo(grouped)
        // Equal grouping, neither with '+': the first the provider returned.
        val first = ContactSuggestion("Ana", "48-601-234-567")
        val second = ContactSuggestion("Ana", "48 601 234 567")
        assertThat(dedupeSuggestions(listOf(first, second)).single()).isEqualTo(first)
    }

    @Test
    fun `separators, spaces, parentheses, dots and a leading plus are formatting only`() {
        val variants =
            listOf(
                "+36 30 000 0001",
                "+36-30-000-0001",
                "(+36) 30.000.0001",
                "36300000001",
                "+36\u00A030\u00A0000\u00A00001", // non-breaking spaces from a formatter
                "\u200E+36 30 000 0001\u200E", // bidi marks from a text layer
            ).map { ContactSuggestion("Ernő Példa", it) }
        assertThat(variants.map { dialIdentity(it.number) }.toSet()).containsExactly("36300000001")
        assertThat(dedupeSuggestions(variants)).hasSize(1)
    }

    @Test
    fun `the same number saved under two labels for one contact collapses to one row`() {
        // Mobile and Work both holding one line: two DATA rows, one identity.
        val twice = listOf(ContactSuggestion("Ernő Példa", "+36300000001"), ContactSuggestion("Ernő Példa", "+36300000001"))
        assertThat(dedupeSuggestions(twice)).hasSize(1)
    }

    @Test
    fun `two different contacts sharing one number both survive`() {
        // A household landline saved under two people: distinct names,
        // distinct rows, both pickable.
        val shared = listOf(ContactSuggestion("Ana Kowalska", "+48 601 234 567"), ContactSuggestion("Jan Kowalski", "+48 601 234 567"))
        assertThat(dedupeSuggestions(shared)).hasSize(2)
    }

    @Test
    fun `local and international forms of a number are NOT merged`() {
        // From an Australian SIM these reach the same phone, but knowing that
        // needs the device's region and dialling rules; merging on a guess
        // silently loses a line the user can text (the #42 failure mode).
        // A duplicate row is the lesser bug, so the dedupe stops at formatting.
        val forms = listOf(ContactSuggestion("David", "+61 414 708 088"), ContactSuggestion("David", "0414 708 088"))
        assertThat(dedupeSuggestions(forms)).hasSize(2)
    }

    @Test
    fun `numbers that share their last ten digits stay distinct - the #42 scar`() {
        // takeLast(10) would key both as 8601234567.
        val polish = ContactSuggestion("Ana", "+48 601 234 567")
        val finnish = ContactSuggestion("Ana", "+358 60 1234567")
        assertThat(dialIdentity(polish.number)).isNotEqualTo(dialIdentity(finnish.number))
        assertThat(dedupeSuggestions(listOf(polish, finnish))).hasSize(2)
    }

    @Test
    fun `only formatting is removed - digits, letters, star and hash are kept literally`() {
        assertThat(dialIdentity("*121#")).isEqualTo("*121#")
        assertThat(dialIdentity("*121*1#")).isNotEqualTo(dialIdentity("*121#"))
        assertThat(dialIdentity("1-800-FLOWERS")).isEqualTo("1800FLOWERS")
        assertThat(dialIdentity("0091 98765 43210")).isNotEqualTo(dialIdentity("+91 98765 43210"))
        assertThat(dialIdentity("++1 555 0100")).isEqualTo("+15550100") // one '+' is a prefix; a second is not formatting
    }

    @Test
    fun `identity is the app's own - a provider with no NORMALIZED_NUMBER changes nothing`() {
        // The identity is computed from NUMBER alone, so a provider that
        // leaves Phone.NORMALIZED_NUMBER null (some OEMs; any unparseable
        // number) dedupes exactly like one that fills it. The query must
        // therefore never depend on that column.
        val source = File("src/main/kotlin/app/clearsms/ui/composemsg/ContactSuggestions.kt").readText()
        assertThat(source).doesNotContain("NORMALIZED_NUMBER,")
        assertThat(source).contains("dialIdentity(number)")
    }

    @Test
    fun `a photo held by any copy is kept on the survivor`() {
        val plain = ContactSuggestion("David", "0859-4188-1970")
        val withPhoto = ContactSuggestion("David", "085941881970", photo)
        val survivor = dedupeSuggestions(listOf(plain, withPhoto)).single()
        assertThat(survivor.number).isEqualTo("0859-4188-1970")
        assertThat(survivor.photoUri).isEqualTo(photo)
    }

    @Test
    fun `survivors keep distinct list keys so the lazy list never collides`() {
        val mixed = report + listOf(ContactSuggestion("Ana", "+48 601 234 567"), ContactSuggestion("Ana", "48601234567"))
        assertThat(dedupeSuggestions(mixed).map { it.listKey }).containsNoDuplicates()
        assertThat(dedupeSuggestions(mixed).map { it.identityKey }).containsNoDuplicates()
    }

    @Test
    fun `the blocked-sender picker and compose share one feed, so one fix covers both`() {
        val src = File("src/main/kotlin/app/clearsms")
        val compose = File(src, "ui/composemsg/ComposeMessageViewModel.kt").readText()
        val settings = File(src, "ui/settings/SettingsViewModel.kt").readText()
        assertThat(compose).contains("contactSuggestionFeed(recipientQuery, contactSuggestions::search)")
        assertThat(settings).contains("contactSuggestionFeed(senderQuery, contactSuggestions::search)")
        val feed = File(src, "ui/composemsg/ContactSuggestionFeed.kt").readText()
        assertThat(feed).contains("dedupeSuggestions(search(text))")
    }
}
