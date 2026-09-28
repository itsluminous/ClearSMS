package app.clearsms.ui.conversation

/**
 * One-shot send outcome consumed by the send snackbars (conversation reply
 * and compose screen).
 *
 * The message's persisted lifecycle lives on its row as
 * [app.clearsms.data.db.DeliveryStatus] - written by
 * [app.clearsms.sms.SmsSender] at dispatch and updated by
 * [app.clearsms.receiver.SmsSentReceiver] from the radio's sent / delivery
 * reports - so bubbles keep the truth across restarts. This enum is only the
 * coarse resolution [SentMessageWatcher] hands back for the snackbar:
 * DELIVERED collapses into [SENT] (a delivered message was necessarily sent),
 * and [SENDING] means an MMS is still awaiting the platform's single result
 * after the window - the caller keeps watching rather than guessing.
 */
enum class SendStatus { SENDING, SENT, FAILED }
