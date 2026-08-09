package app.clearsms.notification

import android.content.Context
import app.clearsms.data.senderid.SenderIdStore
import app.clearsms.sms.ContactInfo
import app.clearsms.sms.ContactsSource
import app.clearsms.ui.components.Brand
import app.clearsms.ui.components.BrandCatalog
import app.clearsms.ui.components.BrandCategory
import app.clearsms.ui.components.initialsOf
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything a notification needs to render a sender: the resolved display
 * name plus the facts for its icon (contact photo, or the tile color and
 * monogram to synthesize one).
 */
data class NotificationSender(
    val name: String,
    val photoUri: String? = null,
    val isContact: Boolean = false,
    /** 1–3 characters drawn on the generated tile when there is no photo. */
    val monogram: String,
    /** Brand tile color (ARGB) when the sender maps to a curated brand; null → hash color. */
    val colorArgb: Int? = null,
    /** Curated brand key - also the bundled logo asset key (`logos/<key>.png`). */
    val brandKey: String? = null,
    /** Curated brand category; drives the badge on generated tiles. */
    val brandCategory: BrandCategory? = null,
    /** True when the bundled sender-ID directory knows this sender. */
    val isKnownSender: Boolean = false,
)

/**
 * Resolves a sender for notifications using the SAME precedence the UI uses
 * (see `resolveSenderDisplay`): saved contact → bundled sender-ID directory →
 * curated brand table → the raw address unchanged.
 *
 * Every tier degrades instead of failing: a lookup that throws (for example
 * a contacts provider error, or READ_CONTACTS being denied - [ContactsSource]
 * already returns null in that case) simply falls through to the next tier,
 * so the notification always renders. Pure so the chain is unit-testable.
 */
fun resolveNotificationSender(
    sender: String,
    contactLookup: (String) -> ContactInfo?,
    directoryLookup: (String) -> String?,
    brandLookup: (String) -> Brand?,
): NotificationSender {
    val contact = runCatching { contactLookup(sender) }.getOrNull()
    if (contact != null) {
        return NotificationSender(
            name = contact.name,
            photoUri = contact.photoUri,
            isContact = true,
            monogram = initialsOf(contact.name),
        )
    }
    // The brand table also supplies tile facts (color, monogram) for names
    // resolved through the directory, so both tiers share one lookup.
    val brand = runCatching { brandLookup(sender) }.getOrNull()
    val directoryName = runCatching { directoryLookup(sender) }.getOrNull()
    val name = directoryName ?: brand?.name ?: sender
    return NotificationSender(
        name = name,
        monogram = brand?.monogram?.take(3)?.ifBlank { null } ?: initialsOf(name),
        colorArgb = brand?.color?.let(::parseHexColor),
        brandKey = brand?.key,
        brandCategory = brand?.category,
        isKnownSender = directoryName != null,
    )
}

/** Parses `#RRGGBB` into an opaque ARGB int; null when malformed. Pure JVM. */
internal fun parseHexColor(hex: String): Int? {
    val digits = hex.removePrefix("#")
    if (digits.length != 6) return null
    return digits.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
}

/**
 * Injectable wrapper wiring [resolveNotificationSender] to the real sources.
 * Lookups may hit the contacts provider (cached with a short TTL), so callers
 * must invoke [resolve] off the main thread - every notifier is driven from
 * the receiver's IO-dispatched application scope. Open so tests can stub the
 * chain without the asset-backed directory.
 */
@Singleton
open class NotificationSenderResolver
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val contactsSource: ContactsSource,
        private val senderIdStore: SenderIdStore,
    ) {
        open fun resolve(sender: String): NotificationSender =
            resolveNotificationSender(
                sender = sender,
                contactLookup = contactsSource::lookup,
                directoryLookup = { senderIdStore.lookup(it)?.name },
                brandLookup = { BrandCatalog.get(context).resolve(it) },
            )
    }
