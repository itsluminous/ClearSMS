package app.clearsms.ui.common

import androidx.annotation.StringRes
import app.clearsms.R
import app.clearsms.sms.SenderRepliability.Repliability

/**
 * The ONE mapping from a sender's [Repliability] to what the conversation's
 * bottom bar says about it - so the wording can never again assert
 * something the app cannot know (GitHub #75: "This sender doesn't accept
 * replies" shown for a carrier short code that very much did).
 *
 * Wording rules, per case:
 * - [Repliability.NUMBER]: nothing to say; the composer is shown.
 * - [Repliability.SHORT_CODE]: sending is possible, acceptance is a guess -
 *   the text hedges ("may not") and a "Reply anyway" action opens the
 *   composer. Never "doesn't".
 * - [Repliability.UNADDRESSABLE_NAME]: a fact about the phone, not the
 *   sender - replies can only be addressed to a number, so the text names
 *   that limit and does not claim the sender refuses anything.
 * - [Repliability.INVALID]: the address is not a destination at all.
 */
object RepliabilityText {
    /** The bottom-bar line for a sender that does not get the composer outright. */
    @StringRes
    fun messageRes(repliability: Repliability): Int =
        when (repliability) {
            Repliability.NUMBER -> error("a subscriber number shows the composer, not a notice")
            Repliability.SHORT_CODE -> R.string.conversation_short_code_may_not_reply
            Repliability.UNADDRESSABLE_NAME -> R.string.conversation_reply_needs_number
            Repliability.INVALID -> R.string.conversation_reply_no_address
        }

    /** Whether the bar offers "Reply anyway" - exactly when sending is technically possible. */
    fun offersReplyAnyway(repliability: Repliability): Boolean = repliability == Repliability.SHORT_CODE

    /** The "Reply anyway" action label. */
    @StringRes
    fun replyAnywayRes(): Int = R.string.conversation_reply_anyway
}
