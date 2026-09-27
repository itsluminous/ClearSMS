package app.clearsms.ui.composemsg

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
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
     */
    val listKey: String get() = name + LIST_KEY_SEPARATOR + number

    private companion object {
        const val LIST_KEY_SEPARATOR = '\u0000'
    }
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
 * Removes rows that would share a [ContactSuggestion.listKey]. The contacts
 * provider returns one row per phone DATA row, so a contact stored in two
 * accounts (or a number saved twice under different labels) yields identical
 * rows; a lazy list keyed on them throws "Key … was used multiple times".
 */
internal fun dedupeSuggestions(suggestions: List<ContactSuggestion>): List<ContactSuggestion> = suggestions.distinctBy { it.listKey }

/**
 * Small UI-side query over the contacts provider for recipient autocomplete.
 * Fails soft (empty list) when READ_CONTACTS has not been granted, and
 * skips - never throws on - any single row an OEM provider hands back in an
 * unexpected shape.
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
            val results = mutableListOf<ContactSuggestion>()
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
                        while (results.size < limit && cursor.moveToNext()) {
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
                            if (results.none { it.listKey == row.listKey }) results += row
                        }
                    }
            } catch (_: SecurityException) {
                // READ_CONTACTS not granted; suggestions are simply unavailable.
            } catch (e: RuntimeException) {
                // Provider-side failure (SQLite, IllegalArgument, remote death):
                // suggestions are unavailable for this keystroke, nothing more.
                Log.w(TAG, "Contact suggestion query failed", e)
            }
            return results
        }

        private companion object {
            const val TAG = "ContactSuggestions"
        }
    }
