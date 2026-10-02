package app.clearsms.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.util.LruCache
import androidx.core.content.ContextCompat
import app.clearsms.diagnostics.Diag
import app.clearsms.diagnostics.DiagField.Companion.flag
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** A saved contact resolved from an address: display name plus optional photo. */
data class ContactInfo(
    val name: String,
    val photoUri: String? = null,
    /** `ContactsContract` lookup URI for opening the contact (ACTION_VIEW). */
    val lookupUri: String? = null,
)

/**
 * Resolves phone-number addresses to saved contacts (name + photo thumbnail)
 * via the contacts provider's `PhoneLookup` table, which natively matches
 * E.164 (`+91XXXXXXXXXX`), national (`0XXXXXXXXXX`) and bare 10-digit forms
 * of the same number.
 *
 * Short codes (`80122`) are looked up through the same table. On AOSP
 * (verified on an API 36 emulator, GitHub #75) the provider matches a
 * short-code address only to a contact saved as exactly that code: with
 * `+4917080122` and `5550080122` saved, `80122` finds nothing, and with
 * `80122` saved, neither long number finds it. The provider's loose suffix
 * matching needs at least seven digits on both sides, so a 3–6 digit
 * address never rides it. Because OEM contacts providers are not all AOSP's,
 * [acceptsMatch] re-checks every short-code hit against the matched row's
 * stored number anyway - a stranger's name must never head a service thread.
 *
 * Results (hits and misses) are cached under a format-insensitive key (see
 * [cacheKeyFor]) with a bounded TTL. A TTL was chosen over a ContentObserver
 * deliberately: an observer can only be registered while READ_CONTACTS is
 * already granted and must be re-registered when the permission arrives
 * mid-session, whereas a short TTL self-heals after both contact edits and
 * late permission grants at the cost of at most one redundant provider query
 * per sender per minute. Lookups are never cached while the permission is
 * missing, so granting it takes effect immediately.
 */
@Singleton
class ContactsSource
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private data class Entry(
            val info: ContactInfo?,
            val cachedAtMs: Long,
        )

        private val cache = LruCache<String, Entry>(CACHE_SIZE)

        /** Returns the saved contact for [address], or null for non-contacts. */
        fun lookup(address: String): ContactInfo? {
            if (!ContactLookupImpl.looksLikePhoneNumber(address)) return null
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }
            val key = cacheKeyFor(address)
            val now = System.currentTimeMillis()
            synchronized(cache) {
                cache.get(key)?.takeIf { now - it.cachedAtMs < TTL_MS }?.let { return it.info }
            }
            val info = queryProvider(address.trim())
            synchronized(cache) { cache.put(key, Entry(info, now)) }
            return info
        }

        /** Drops all cached entries, e.g. after READ_CONTACTS is granted. */
        fun invalidate() {
            synchronized(cache) { cache.evictAll() }
        }

        private fun queryProvider(address: String): ContactInfo? {
            val uri =
                Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(address),
                )
            val projection =
                arrayOf(
                    ContactsContract.PhoneLookup.DISPLAY_NAME,
                    ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI,
                    ContactsContract.PhoneLookup._ID,
                    ContactsContract.PhoneLookup.LOOKUP_KEY,
                    ContactsContract.PhoneLookup.NUMBER,
                )
            return try {
                context.contentResolver
                    .query(uri, projection, null, null, null)
                    ?.use { cursor -> firstAcceptedMatch(address, cursor) }
            } catch (e: Exception) {
                // Explains "names stopped resolving": a denied lookup means
                // the permission went away; anything else is a provider fault.
                Diag.w(TAG, "contact lookup failed", e, flag("denied", e is SecurityException))
                null
            }
        }

        /**
         * The first row the provider returned that [acceptsMatch] lets
         * through. For a subscriber number that is simply the first row; for
         * a short code it is the row saved as exactly that code, however the
         * provider ordered its results.
         */
        private fun firstAcceptedMatch(
            address: String,
            cursor: Cursor,
        ): ContactInfo? {
            while (cursor.moveToNext()) {
                val number = if (cursor.isNull(NUMBER_COLUMN)) null else cursor.getString(NUMBER_COLUMN)
                if (!acceptsMatch(address, number)) continue
                val name = cursor.getString(0) ?: continue
                return ContactInfo(
                    name = name,
                    photoUri = if (cursor.isNull(1)) null else cursor.getString(1),
                    lookupUri = lookupUriAt(cursor),
                )
            }
            return null
        }

        /**
         * Stable lookup URI for the matched row (for ACTION_VIEW on the
         * contact), or null when either column is missing.
         */
        private fun lookupUriAt(cursor: Cursor): String? {
            if (cursor.isNull(2) || cursor.isNull(3)) return null
            return ContactsContract.Contacts
                .getLookupUri(cursor.getLong(2), cursor.getString(3))
                ?.toString()
        }

        companion object {
            private const val TAG = "ContactsSource"
            private const val CACHE_SIZE = 256

            /** Cache entries expire after a minute; see the class KDoc for why. */
            const val TTL_MS = 60_000L

            /**
             * Format-insensitive cache key: the last 10 digits of the address,
             * so `+91 98765 43210`, `09876543210` and `9876543210` share one
             * cache entry - mirroring how `PhoneLookup` matches them to the
             * same contact. A short code keeps its whole digit string, so
             * `80122` (5 digits) and `+4917080122` (key `4917080122`) can
             * never share an entry: a key is 10 digits long or the address's
             * own length, and no address under 10 digits can collide with one
             * that long.
             */
            fun cacheKeyFor(address: String): String {
                val digits = address.filter { it.isDigit() }
                return if (digits.length > NATIONAL_NUMBER_DIGITS) digits.takeLast(NATIONAL_NUMBER_DIGITS) else digits
            }

            /**
             * Whether a `PhoneLookup` row with stored [matchedNumber] may be
             * shown for [address]. Two subscriber numbers trust the provider
             * (that is what its E.164 / national / bare matching is for). When
             * either side is a short code - fewer than
             * [SenderRepliability.NUMBER_MIN_DIGITS] digits - the row is
             * accepted only when its digits are exactly the address's digits:
             * `80122` must not borrow the name saved for `+4917080122`, nor
             * `+4917080122` the name saved for `80122`. A row without a stored
             * number cannot be checked and is refused for a short-code address.
             */
            fun acceptsMatch(
                address: String,
                matchedNumber: String?,
            ): Boolean {
                val digits = address.filter { it.isDigit() }
                val matched = matchedNumber?.filter { it.isDigit() }
                if (digits.length < SenderRepliability.NUMBER_MIN_DIGITS) return matched == digits
                if (matched != null && matched.length < SenderRepliability.NUMBER_MIN_DIGITS) return matched == digits
                return true
            }

            private const val NATIONAL_NUMBER_DIGITS = 10

            /** Index of [ContactsContract.PhoneLookup.NUMBER] in the lookup projection. */
            private const val NUMBER_COLUMN = 4
        }
    }
