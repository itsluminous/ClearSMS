package app.clearsms.ui.composemsg

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Issue #48: the contact picker crashed for one reporter on queries that
 * surfaced one particular contact. The mechanism that fits is a duplicate
 * lazy-list key (a contact stored twice yields two identical provider rows;
 * Compose throws "Key … was used multiple times"). These tests pin the
 * per-row sanitising and the dedupe that make ONE bad or duplicated row
 * unable to take the picker down. Synthetic fixtures only.
 */
class ContactSuggestionRowTest {
    @Test
    fun `duplicate provider rows collapse to one suggestion`() {
        val twice =
            listOf(
                ContactSuggestion("Ernő Példa", "+36300000001", "content://com.android.contacts/contacts/7/photo"),
                ContactSuggestion("Ernő Példa", "+36300000001", "content://com.android.contacts/contacts/7/photo"),
                ContactSuggestion("Ernő Példa", "+36300000002"),
            )
        val deduped = dedupeSuggestions(twice)
        assertThat(deduped).hasSize(2)
        assertThat(deduped.map { it.listKey }).containsNoDuplicates()
    }

    @Test
    fun `list keys do not collide across a name-number boundary shift`() {
        // "Ab"+"1" and "A"+"b1" concatenate to the same string; the key must not.
        val a = ContactSuggestion(name = "Ab", number = "1")
        val b = ContactSuggestion(name = "A", number = "b1")
        assertThat(a.listKey).isNotEqualTo(b.listKey)
    }

    @Test
    fun `a malformed photo uri is dropped so the letter avatar renders`() {
        assertThat(usablePhotoUri("not a uri")).isNull()
        assertThat(usablePhotoUri("")).isNull()
        assertThat(usablePhotoUri("   ")).isNull()
        assertThat(usablePhotoUri("://missing-scheme")).isNull()
        // No network fetcher is registered (the app has no INTERNET permission).
        assertThat(usablePhotoUri("https://example.invalid/photo.jpg")).isNull()
        assertThat(
            contactSuggestionRow("Per", "+36300000003", "garbage")?.photoUri,
        ).isNull()
    }

    @Test
    fun `well-formed contact photo uris are kept verbatim`() {
        val contact = "content://com.android.contacts/contacts/42/photo"
        val display = "content://com.android.contacts/display_photo/42"
        val file = "file:///data/user/0/app.clearsms/cache/p.jpg"
        assertThat(usablePhotoUri(contact)).isEqualTo(contact)
        assertThat(usablePhotoUri(display)).isEqualTo(display)
        assertThat(usablePhotoUri(file)).isEqualTo(file)
        assertThat(usablePhotoUri("  $contact ")).isEqualTo(contact)
    }

    @Test
    fun `a blank or missing name falls back to the number`() {
        assertThat(contactSuggestionRow(null, "+36300000004", null)?.name).isEqualTo("+36300000004")
        assertThat(contactSuggestionRow("   ", "+36300000004", null)?.name).isEqualTo("+36300000004")
        assertThat(contactSuggestionRow("", " +36300000004 ", null)?.number).isEqualTo("+36300000004")
    }

    @Test
    fun `a row without a number is skipped`() {
        assertThat(contactSuggestionRow("Erny", null, null)).isNull()
        assertThat(contactSuggestionRow("Erny", "   ", null)).isNull()
    }

    @Test
    fun `an accented name survives intact and produces a key`() {
        val row = contactSuggestionRow(" Ernő Ágnes-Pernille ", "+36300000005", null)
        assertThat(row?.name).isEqualTo("Ernő Ágnes-Pernille")
        assertThat(row?.listKey).contains("Ernő")
    }

    @Test
    fun `a very long name is clipped, never rejected`() {
        val long = "P".repeat(5_000)
        val row = contactSuggestionRow(long, "+36300000006", null)
        assertThat(row).isNotNull()
        assertThat(row!!.name).hasLength(MAX_SUGGESTION_NAME_LENGTH)
    }

    @Test
    fun `picker rows are keyed on listKey and the provider query fails soft`() {
        val src = File("src/main/kotlin/app/clearsms")
        val screen = File(src, "ui/composemsg/ComposeMessageScreen.kt").readText()
        assertThat(screen).contains("key = { it.listKey }")
        assertThat(screen).doesNotContain("key = { it.name + it.number }")
        val search = File(src, "ui/composemsg/ContactSuggestions.kt").readText()
        // One unreadable row is skipped; a provider-side failure yields no list, not a crash.
        assertThat(search).contains("catch (e: RuntimeException)")
        assertThat(search).contains("contactSuggestionRow(")
    }
}
