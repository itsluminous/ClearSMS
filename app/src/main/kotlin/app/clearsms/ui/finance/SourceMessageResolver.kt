package app.clearsms.ui.finance

import app.clearsms.data.db.MessageEntity

/** Navigation target for the SMS a finance/alert card was derived from. */
data class MessageRef(
    val threadId: Long,
    val messageId: Long,
)

/**
 * Resolves a stored raw message into its conversation target.
 *
 * Returns null when the source message no longer exists (deleted), so the
 * caller can disable the click-through instead of crashing.
 */
object SourceMessageResolver {
    fun resolve(message: MessageEntity?): MessageRef? = message?.let { MessageRef(threadId = it.threadId, messageId = it.id) }
}
