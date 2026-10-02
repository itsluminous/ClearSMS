package app.clearsms.sms

import android.telephony.PhoneNumberUtils

/**
 * What an SMS reply to a sender address CAN do - decided from what the
 * platform can encode, never from a guess about the service behind it.
 *
 * The GSM submit PDU addresses a destination as BCD digits
 * (`PhoneNumberUtils.networkPortionToCalledPartyBCD`): `extractNetworkPortion`
 * keeps only `0-9 * # +` and silently DROPS every letter. So an
 * alphanumeric sender id is not "a sender that refuses replies" - it is an
 * address the phone cannot encode at all: `VM-HDFCBK` becomes an empty
 * destination (a null PDU, reported back as a generic failure) and a mixed
 * id like `O2` would go out to the wrong destination `2`. That is why
 * [Repliability.UNADDRESSABLE_NAME] keeps the composer closed.
 *
 * Numeric short codes (`80122`, `56767`, `139`) are different: they are
 * ordinary SMS destinations and many are genuinely two-way (reply `WEITER`
 * to a German carrier to re-enable data - GitHub #75; reply `STOP` to opt
 * out). Whether a particular code answers is the service's decision, which
 * this app cannot know in advance - so the verdict is
 * [Repliability.SHORT_CODE]: sending is possible, acceptance is a guess, and
 * the UI must say so instead of asserting a refusal. The platform's own
 * premium-short-code check (`SMSDispatcher.checkDestination`) still runs on
 * every such send and asks the user before anything billable goes out.
 *
 * This is the single shared predicate - the notification planner delegates
 * here instead of keeping its own copy.
 */
object SenderRepliability {
    /** The verdict for one address; see the object doc for why each exists. */
    enum class Repliability {
        /** 7–15 digits with an optional `+`: a subscriber number, replies expected to work. */
        NUMBER,

        /** 3–6 digits: a short code. Addressable; whether it accepts replies is unknown. */
        SHORT_CODE,

        /** Contains letters: the phone cannot encode this destination (and may mangle it). */
        UNADDRESSABLE_NAME,

        /** Empty, 1–2 digits, over 15 digits, or stray symbols: not a destination at all. */
        INVALID,

        ;

        /** Whether a reply can be handed to the radio at all. */
        val addressable: Boolean get() = this == NUMBER || this == SHORT_CODE
    }

    /**
     * Pure-Kotlin classification (unit-testable on the JVM without
     * Robolectric). Spaces, dashes and parentheses are formatting and are
     * ignored; anything else that is not a digit or a leading `+` makes the
     * address unaddressable (letters) or invalid (other symbols).
     *
     * Floor and ceiling: 15 digits is E.164's maximum. The floor is
     * [SHORT_CODE_MIN_DIGITS] = 3 - the shortest real SMS short codes in use
     * are three digits (Indian Railways' `139`, several European service
     * codes); no network routes SMS to a one- or two-digit address, and the
     * platform's own premium-code tables start at three digits too. Letting
     * `1` or `22` through would only produce a guaranteed failure.
     */
    fun classify(address: String): Repliability {
        val compact = address.filterNot { it == ' ' || it == '-' || it == '(' || it == ')' }
        if (compact.any { it.isLetter() }) return Repliability.UNADDRESSABLE_NAME
        val digits = compact.removePrefix("+")
        if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return Repliability.INVALID
        return when (digits.length) {
            in NUMBER_MIN_DIGITS..E164_MAX_DIGITS -> Repliability.NUMBER
            in SHORT_CODE_MIN_DIGITS until NUMBER_MIN_DIGITS -> Repliability.SHORT_CODE
            else -> Repliability.INVALID
        }
    }

    /** True when the phone can hand a reply to [address] to the radio (a number OR a short code). */
    fun isAddressable(address: String): Boolean = classify(address).addressable

    /**
     * Platform-aware verdict: the pure core decides, with
     * [PhoneNumberUtils.isWellFormedSmsAddress] / [PhoneNumberUtils.isGlobalPhoneNumber]
     * consulted as a cross-check where the framework is available (both
     * accept numeric short codes). Off device (plain-JVM tests) the
     * framework stubs throw, and the core verdict stands.
     */
    fun isRepliable(address: String): Boolean {
        if (!isAddressable(address)) return false
        return runCatching {
            PhoneNumberUtils.isWellFormedSmsAddress(address) ||
                PhoneNumberUtils.isGlobalPhoneNumber(address)
        }.getOrDefault(true)
    }

    /**
     * [classify] with the platform consulted: an addressable verdict that
     * [isRepliable] nonetheless rejects on-device degrades to
     * [Repliability.INVALID]. Off device the pure verdict stands.
     */
    fun classifyOnDevice(address: String): Repliability {
        val verdict = classify(address)
        return if (verdict.addressable && !isRepliable(address)) Repliability.INVALID else verdict
    }

    /** Shortest address treated as a real destination (see [classify]). */
    const val SHORT_CODE_MIN_DIGITS = 3

    /** From this many digits an address reads as a subscriber number rather than a short code. */
    const val NUMBER_MIN_DIGITS = 7

    /** E.164's maximum. */
    const val E164_MAX_DIGITS = 15
}
