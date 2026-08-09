package app.clearsms.ui.components

import app.clearsms.sms.ContactInfo

/** Everything a row needs to render a sender: resolved name, photo and origin. */
data class SenderDisplay(
    val name: String,
    val photoUri: String? = null,
    /** The name came from the user's address book. */
    val isContact: Boolean = false,
    /** The name came from the bundled sender ID directory. */
    val isKnownSender: Boolean = false,
)

/**
 * Resolves how a sender is displayed, in priority order:
 *
 * 1. a saved contact (name + photo) - people always win,
 * 2. the bundled sender ID directory (brand name),
 * 3. the raw address unchanged.
 *
 * Pure so the precedence (including +91 / 0-prefixed / bare 10-digit contact
 * matches, which the injected [contactLookup] performs) is unit-testable.
 */
fun resolveSenderDisplay(
    sender: String,
    contactLookup: (String) -> ContactInfo?,
    directoryLookup: (String) -> String?,
): SenderDisplay {
    contactLookup(sender)?.let { contact ->
        return SenderDisplay(name = contact.name, photoUri = contact.photoUri, isContact = true)
    }
    directoryLookup(sender)?.let { name ->
        return SenderDisplay(name = name, isKnownSender = true)
    }
    return SenderDisplay(name = sender)
}
