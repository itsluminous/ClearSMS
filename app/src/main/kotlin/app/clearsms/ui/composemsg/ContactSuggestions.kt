package app.clearsms.ui.composemsg

import android.content.Context
import android.provider.ContactsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** A contact suggestion for the recipient field. */
data class ContactSuggestion(
    val name: String,
    val number: String,
    /** Contact photo thumbnail (content URI), when the contact has one. */
    val photoUri: String? = null,
)

/**
 * Small UI-side query over the contacts provider for recipient autocomplete.
 * Fails soft (empty list) when READ_CONTACTS has not been granted.
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
                        while (cursor.moveToNext() && results.size < limit) {
                            val name = cursor.getString(0) ?: continue
                            val number = cursor.getString(1) ?: continue
                            val photoUri = if (cursor.isNull(2)) null else cursor.getString(2)
                            results += ContactSuggestion(name = name, number = number, photoUri = photoUri)
                        }
                    }
            } catch (_: SecurityException) {
                // READ_CONTACTS not granted; suggestions are simply unavailable.
            }
            return results
        }
    }
