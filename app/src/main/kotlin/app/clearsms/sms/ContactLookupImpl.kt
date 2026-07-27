package app.clearsms.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import android.util.LruCache
import androidx.core.content.ContextCompat
import app.clearsms.domain.categorizer.ContactLookup
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ContactLookup] backed by the contacts provider's `PhoneLookup` table.
 *
 * Results (hits and misses) are cached, and alphanumeric sender IDs are
 * rejected up front — TRAI-route senders like "VM-HDFCBK" can never be a
 * contact, so no provider query is made for them.
 */
@Singleton
class ContactLookupImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ContactLookup {
        private val cache = LruCache<String, Boolean>(CACHE_SIZE)

        override fun isContact(address: String): Boolean {
            if (!looksLikePhoneNumber(address)) return false
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            val key = address.trim()
            cache.get(key)?.let { return it }
            val result = queryProvider(key)
            cache.put(key, result)
            return result
        }

        private fun queryProvider(address: String): Boolean {
            val uri =
                Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(address),
                )
            return try {
                context.contentResolver
                    .query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)
                    ?.use { it.count > 0 }
                    ?: false
            } catch (e: Exception) {
                Log.w(TAG, "Contact lookup failed", e)
                false
            }
        }

        companion object {
            private const val TAG = "ContactLookup"
            private const val CACHE_SIZE = 256

            /**
             * True when [address] plausibly denotes a dialable phone number
             * (digits with optional +, spaces, dashes or parentheses) rather
             * than an alphanumeric sender ID.
             */
            fun looksLikePhoneNumber(address: String): Boolean {
                val trimmed = address.trim()
                if (trimmed.isEmpty()) return false
                val digits = trimmed.count { it.isDigit() }
                if (digits < MIN_DIGITS) return false
                return trimmed.all { it.isDigit() || it in "+-() ." }
            }

            private const val MIN_DIGITS = 5
        }
    }
