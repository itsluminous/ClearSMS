package app.clearsms.domain.categorizer

/**
 * Checks whether an address belongs to a contact in the user's address book.
 *
 * The platform layer provides the real implementation backed by the contacts
 * provider; the domain layer only depends on this interface.
 */
fun interface ContactLookup {
    fun isContact(address: String): Boolean
}
