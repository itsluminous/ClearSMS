package app.clearsms.sms

import app.clearsms.domain.categorizer.ContactLookup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ContactLookup] backed by [ContactsSource], so categorization ("is this a
 * contact?") and display ("what is this contact's name/photo?") share one
 * provider query path and one cache.
 *
 * Alphanumeric sender IDs are rejected up front — TRAI-route senders like
 * "VM-HDFCBK" can never be a contact, so no provider query is made for them.
 */
@Singleton
class ContactLookupImpl
    @Inject
    constructor(
        private val contactsSource: ContactsSource,
    ) : ContactLookup {
        override fun isContact(address: String): Boolean = contactsSource.lookup(address) != null

        companion object {
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
