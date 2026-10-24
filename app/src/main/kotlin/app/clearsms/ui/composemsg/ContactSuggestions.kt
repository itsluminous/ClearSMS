package app.clearsms.ui.composemsg

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import app.clearsms.diagnostics.Diag
import app.clearsms.diagnostics.DiagField.Companion.count
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** A contact suggestion for the recipient field. */
data class ContactSuggestion(
    val name: String,
    val number: String,
    /** Contact photo thumbnail (content URI), when the contact has one. */
    val photoUri: String? = null,
) {
    /**
     * Identity of this row in a lazy list. Name and number are joined with a
     * NUL separator (a character neither field can contain), so "Ab"+"1" and
     * "A"+"b1" never share a key. Two rows with the SAME name and number are
     * the same contact stored twice (two accounts, or one number saved under
     * two labels); [dedupeSuggestions] removes those before they reach a list.
     * Rows that survive it have distinct [identityKey]s, and a distinct
     * identity implies a distinct list key (either the names differ, or the
     * raw numbers do), so a deduped list never collides here.
     */
    val listKey: String get() = name + LIST_KEY_SEPARATOR + number

    /**
     * What this row denotes to the user: the name shown and the line the
     * number reaches, with the number's formatting removed ([dialIdentity]).
     * The contacts provider returns one row per phone DATA row, and a contact
     * synced through several accounts gets one per account, each in that
     * adapter's own formatting (`0859-4188-1970` and `085941881970` for the
     * same line - issue #127). Two rows with one identity are the same
     * number stored twice, never two numbers the user could text.
     */
    internal val identityKey: String get() = name + LIST_KEY_SEPARATOR + dialIdentity(number)

    private companion object {
        const val LIST_KEY_SEPARATOR = '\u0000'
    }
}

/**
 * Characters a formatter or sync adapter inserts between the digits of a
 * stored number and that change nothing about where it dials: ASCII and
 * typographic dashes, dots, slashes, grouping parentheses, and the invisible
 * bidi marks a text layer wraps a number in. Whitespace (all of Unicode's,
 * including the non-breaking spaces formatters favour) is handled alongside.
 */
private const val NUMBER_FORMATTING_CHARS =
    "-.()/\u2010\u2011\u2012\u2013\u2014\u2212" +
        "\u200E\u200F\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069"

private fun Char.isNumberFormatting(): Boolean = isWhitespace() || this in NUMBER_FORMATTING_CHARS

/**
 * The number with its formatting stripped and NOTHING else changed: every
 * separator ([isNumberFormatting]) goes, and one leading `+` goes (a stored
 * `+61…` and `61…` denote the same line). Digits, `*`, `#`, letters and any
 * other character stay exactly as they are, so two strings only share an
 * identity when they are the same characters dressed differently.
 *
 * Deliberately NOT region-aware: `+61 414 708 088` and `0414 708 088` keep
 * separate identities even though, from an Australian SIM, they reach the
 * same phone. Deciding that needs the device's country and the national
 * dialling rules (which is `SenderNormalizer`'s job for message senders),
 * and getting it wrong merges two genuinely different numbers - the user
 * silently loses a line they could text, as #42's `takeLast(10)` did to
 * distinct Polish numbers. A duplicate row is the lesser bug, so this stops
 * at formatting. The provider's own `NORMALIZED_NUMBER` is not used either:
 * it is region-aware in exactly that way, and it is null on some OEM
 * providers and for any number the provider could not parse, so rows of one
 * contact can disagree on whether they have it and would split on it.
 */
internal fun dialIdentity(number: String): String {
    val stripped = number.filterNot { it.isNumberFormatting() }
    return stripped.removePrefix("+")
}

/** Longest display name a suggestion row will carry; longer names are clipped. */
internal const val MAX_SUGGESTION_NAME_LENGTH = 200

private val URI_WITH_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:.+")
private val PHOTO_SCHEMES = setOf("content", "file", "android.resource")

/**
 * Turns one raw contacts-provider row into a suggestion, or null when the
 * row is unusable. Pure so it is unit-testable without a provider:
 * - a null or blank number is skipped (nothing to send to);
 * - a null or blank name falls back to the number (the row stays useful);
 * - the name is trimmed and clipped to [MAX_SUGGESTION_NAME_LENGTH];
 * - a photo URI is kept only if it parses as a URI with a scheme the image
 *   pipeline can open; anything else becomes null, which renders the letter
 *   avatar instead of handing an odd string to the image loader.
 */
internal fun contactSuggestionRow(
    name: String?,
    number: String?,
    photoUri: String?,
): ContactSuggestion? {
    val cleanNumber = number?.trim().orEmpty()
    if (cleanNumber.isEmpty()) return null
    val cleanName =
        name
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(MAX_SUGGESTION_NAME_LENGTH)
            ?: cleanNumber
    return ContactSuggestion(name = cleanName, number = cleanNumber, photoUri = usablePhotoUri(photoUri))
}

/** Keeps a photo URI only when it has a scheme the image pipeline can open. */
internal fun usablePhotoUri(photoUri: String?): String? {
    val trimmed = photoUri?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (!URI_WITH_SCHEME.matches(trimmed)) return null
    val scheme = trimmed.substringBefore(':').lowercase()
    return trimmed.takeIf { scheme in PHOTO_SCHEMES }
}

/**
 * Collapses rows that share a [ContactSuggestion.identityKey] - the same
 * contact's same number stored more than once (two accounts, two labels, or
 * one account's copy formatted differently from another's; issue #127) - to
 * ONE row each, in order of first appearance. Keyed on the number's identity
 * rather than its text because a lazy list keyed on identical rows throws
 * "Key … was used multiple times" (#48), and because differently-formatted
 * copies are what the reporter saw as four rows for a two-number contact.
 *
 * The survivor is the most readable copy: the one with the most grouping
 * separators (`0859-4188-1970` over `085941881970`), then the one carrying
 * a leading `+`, then the first the provider returned. Separators mean a
 * person or a formatter already grouped the digits for reading; an unbroken
 * digit run is a sync adapter's storage form. Whichever copy wins, a photo
 * held by any of the copies is kept, since all of them picture one contact.
 */
internal fun dedupeSuggestions(suggestions: List<ContactSuggestion>): List<ContactSuggestion> {
    val copies = LinkedHashMap<String, MutableList<ContactSuggestion>>()
    for (suggestion in suggestions) copies.getOrPut(suggestion.identityKey) { mutableListOf() } += suggestion
    return copies.values.map { group -> mostReadable(group) }
}

private val READABILITY =
    compareBy<ContactSuggestion>(
        { suggestion -> suggestion.number.count { it.isNumberFormatting() } },
        { suggestion -> suggestion.number.startsWith('+') },
    )

private fun mostReadable(group: List<ContactSuggestion>): ContactSuggestion {
    // maxWith keeps the FIRST of equally readable copies: provider order wins ties.
    val best = group.maxWith(READABILITY)
    val photo = best.photoUri ?: group.firstNotNullOfOrNull { it.photoUri }
    return if (photo == best.photoUri) best else best.copy(photoUri = photo)
}

/**
 * Small UI-side query over the contacts provider for recipient autocomplete.
 * Fails soft (empty list) when READ_CONTACTS has not been granted, and
 * skips - never throws on - any single row an OEM provider hands back in an
 * unexpected shape.
 *
 * `CONTACT_ID` is deliberately not selected. A row is identified by what it
 * shows (the name) and where it dials ([dialIdentity]); two rows that agree
 * on both render identically and send to the same line whichever is tapped,
 * so telling them apart by contact id would only put an indistinguishable
 * duplicate back on screen. Two people who share a name but not a number
 * already differ in identity and both appear.
 */
@Singleton
class ContactSuggestions
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun search(
            query: String,
            limit: Int = 10,
        ): List<ContactSuggestion> {
            if (query.isBlank()) return emptyList()
            var results: List<ContactSuggestion> = emptyList()
            try {
                context.contentResolver
                    .query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER,
                            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
                        ),
                        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR " +
                            "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?",
                        arrayOf("%$query%", "%$query%"),
                        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
                    )?.use { cursor ->
                        // Every copy of a kept contact is read before the
                        // dedupe picks its survivor, so [limit] counts
                        // distinct identities, not provider rows: reading
                        // stops at the row that would start the (limit+1)th.
                        // Copies of one contact are adjacent (the sort is by
                        // name, and copies share theirs), so none is cut off.
                        val rows = mutableListOf<ContactSuggestion>()
                        val identities = HashSet<String>()
                        while (cursor.moveToNext()) {
                            val row =
                                try {
                                    contactSuggestionRow(
                                        name = cursor.getString(0),
                                        number = cursor.getString(1),
                                        photoUri = if (cursor.isNull(2)) null else cursor.getString(2),
                                    )
                                } catch (e: RuntimeException) {
                                    // A short or oddly-typed row from an OEM provider:
                                    // skip it, keep the rest of the list.
                                    Log.w(TAG, "Skipping unreadable contact row", e)
                                    null
                                } ?: continue
                            if (row.identityKey !in identities) {
                                if (identities.size >= limit) break
                                identities += row.identityKey
                            }
                            rows += row
                        }
                        results = dedupeSuggestions(rows)
                    }
            } catch (_: SecurityException) {
                // READ_CONTACTS not granted; suggestions are simply unavailable.
            } catch (e: RuntimeException) {
                // Provider-side failure (SQLite, IllegalArgument, remote death):
                // suggestions are unavailable for this keystroke, nothing more.
                // Logged to the report: it is the answer to "no names come up
                // when I type". The typed prefix travels as its length only.
                Diag.w(TAG, "contact suggestion query failed", e, count("queryChars", query.length))
            }
            return results
        }

        private companion object {
            const val TAG = "ContactSuggestions"
        }
    }
