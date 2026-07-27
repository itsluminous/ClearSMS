package app.clearsms.sms

import android.telephony.PhoneNumberUtils

/**
 * Decides whether a sender address can actually receive an SMS reply.
 *
 * Alphanumeric TRAI sender ids ("VM-HDFCBK", "AD-AMAZON", "AX-AMZNIN") and
 * short codes ("56767") are one-way routes: replying to them is impossible,
 * so the conversation composer and the notification REPLY action are hidden
 * for such senders. This is the single shared predicate — the notification
 * planner delegates here instead of keeping its own copy.
 */
object SenderRepliability {
    /**
     * Pure-Kotlin core (unit-testable on the JVM without Robolectric): an
     * address is dialable when, after stripping spaces / dashes /
     * parentheses, it is an optional `+` followed by 7–15 digits (E.164's
     * length bounds).
     */
    fun isDialableNumber(address: String): Boolean {
        val compact = address.filterNot { it == ' ' || it == '-' || it == '(' || it == ')' }
        return PHONE_REGEX.matches(compact)
    }

    /**
     * Platform-aware verdict: the pure core decides, with
     * [PhoneNumberUtils.isWellFormedSmsAddress] / [PhoneNumberUtils.isGlobalPhoneNumber]
     * consulted as a cross-check where the framework is available. Off
     * device (plain-JVM tests) the framework stubs throw, and the core
     * verdict stands.
     */
    fun isRepliable(address: String): Boolean {
        if (!isDialableNumber(address)) return false
        return runCatching {
            PhoneNumberUtils.isWellFormedSmsAddress(address) ||
                PhoneNumberUtils.isGlobalPhoneNumber(address)
        }.getOrDefault(true)
    }

    private val PHONE_REGEX = Regex("^\\+?\\d{7,15}$")
}
