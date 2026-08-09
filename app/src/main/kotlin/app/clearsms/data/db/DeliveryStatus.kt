package app.clearsms.data.db

/**
 * Persisted send lifecycle of an OUTGOING message (null on incoming rows).
 *
 * Transitions, all recorded against the row so they survive restarts:
 *
 * - [SCHEDULED] - written when the user schedules the message for a later
 *   time instead of sending; [app.clearsms.work.MessageScheduler] fires it
 *   into the normal dispatch path at the chosen time (row flips to
 *   [SENDING]). Cancelling a schedule deletes the row - it was never sent.
 * - [SENDING] - written at dispatch by [app.clearsms.sms.SmsSender].
 * - [SENT] - the radio's sent report came back OK
 *   ([app.clearsms.receiver.SmsSentReceiver]), or no failure was recorded
 *   within the result window (see [app.clearsms.ui.conversation.SentMessageWatcher]).
 * - [DELIVERED] - a carrier delivery report arrived. Requires the delivery
 *   reports setting to be on AND the carrier to actually send one; a message
 *   is otherwise honestly left at [SENT], never upgraded speculatively.
 * - [FAILED] - the send call threw or the radio reported a failure.
 */
enum class DeliveryStatus { SCHEDULED, SENDING, SENT, DELIVERED, FAILED }
