package app.clearsms.sms

import app.clearsms.data.db.DeliveryStatus
import app.clearsms.data.db.MessageDao
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.mms.MmsSender
import app.clearsms.mms.StagedAttachment
import app.clearsms.work.MessageScheduler
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The ONE place a plain Send is turned into a dispatch, for every compose
 * bar - the conversation's and the new-message screen's alike (issue
 * #119: the standalone composer used to call the senders directly and so
 * silently skipped the delay the conversation screen honoured).
 *
 * What it decides:
 * - attachments staged: an MMS, dispatched NOW. Delayed sending is
 *   SMS-only today (scheduling is - see the schedule affordance hiding
 *   with chips staged), so an MMS never becomes a schedule that would
 *   lose its files;
 * - otherwise, delayed sending ON (GitHub #40, opt-in): a short SCHEDULE
 *   through [MessageScheduler] - the same durable AlarmManager +
 *   SCHEDULED-row machinery as long-press scheduling, so the message still
 *   goes out if the user leaves the thread or the process dies mid-delay
 *   (rearmAll fires overdue rows after a reboot) - reported as
 *   [Outcome.Delayed] so the screen can offer Cancel;
 * - otherwise an SMS, dispatched now.
 *
 * [cancelDelayed] is the Cancel half: it resolves the cancel/fire race
 * exactly-once (see [MessageScheduler.cancelDelayed]) and hands back the
 * text the user TYPED, not the accent-folded wire body the scheduler
 * persisted, so the composer gets exactly what was there. The originals
 * are kept here, in the singleton, so a delayed send started on one
 * screen can be cancelled on another (the composer navigates into the
 * thread it just created) without the text degrading on the way.
 */
@Singleton
class OutgoingSendDispatcher
    @Inject
    constructor(
        private val settings: SettingsRepository,
        private val messageScheduler: MessageScheduler,
        private val smsSender: SmsSender,
        private val mmsSender: MmsSender,
        private val messageDao: MessageDao,
    ) {
        sealed interface Outcome {
            /** The Room row the send persisted (Sending, or Scheduled when delayed). */
            val messageId: Long

            /** Handed to the radio (SMS) or the platform MMS service: the status resolves on the row. */
            data class Dispatched(
                override val messageId: Long,
            ) : Outcome

            /** Pending: fires in [delaySeconds] unless [cancelDelayed] wins first. */
            data class Delayed(
                override val messageId: Long,
                val delaySeconds: Int,
            ) : Outcome
        }

        /**
         * The exact text handed to [send] for each still-pending delayed
         * send, so Cancel restores what was typed. In-memory on purpose:
         * the pending snackbar dies with the process anyway, and the
         * message itself stays durable in Room regardless.
         */
        private val pendingOriginals = ConcurrentHashMap<Long, String>()

        /**
         * Dispatches [body] (with [attachments], as an MMS) to [destination]
         * on [subscriptionId], or schedules it [DelayedSendDelay] from
         * [nowMs] when delayed sending applies. Throws when nothing could be
         * persisted - the caller then has nothing to retry against and
         * restores the text itself.
         */
        suspend fun send(
            destination: String,
            body: String,
            attachments: List<StagedAttachment>,
            subscriptionId: Int?,
            nowMs: Long = System.currentTimeMillis(),
        ): Outcome {
            if (attachments.isNotEmpty()) {
                return Outcome.Dispatched(mmsSender.send(destination, body, attachments, subscriptionId))
            }
            if (settings.delayedSendEnabled.first()) {
                val delay = settings.delayedSendDelay.first()
                val messageId = messageScheduler.schedule(destination, body, subscriptionId, nowMs + delay.millis)
                pendingOriginals[messageId] = body
                return Outcome.Delayed(messageId, delay.seconds)
            }
            return Outcome.Dispatched(smsSender.send(destination, body, subscriptionId))
        }

        /**
         * Cancel tapped on the pending bar. Exactly one of two outcomes,
         * decided by the DAO's compare-and-set (never both):
         * - cancel won: the row is gone, nothing was ever sent, and the
         *   returned text (the typed original, else the persisted body)
         *   goes BACK into the composer - a cancel that discarded the text
         *   would be data loss;
         * - the fire won: null - the message is on its way, so restoring
         *   its text would duplicate it; the caller reports an honest Sent.
         */
        suspend fun cancelDelayed(messageId: Long): String? {
            val persisted = messageScheduler.cancelDelayed(messageId)
            val original = pendingOriginals.remove(messageId)
            return persisted?.let { original ?: it }
        }

        /**
         * Seconds left on a delayed send that is STILL pending, for a screen
         * that inherits it (the composer hands its delayed send to the
         * conversation it navigates into); null once it fired, was
         * cancelled, or never existed - then there is nothing to cancel and
         * no bar is shown. Rounded up, never below one: the bar must outlive
         * the alarm, not expire first.
         */
        suspend fun pendingDelaySeconds(
            messageId: Long,
            nowMs: Long = System.currentTimeMillis(),
        ): Int? {
            val row = messageDao.getById(messageId)?.takeIf { it.deliveryStatus == DeliveryStatus.SCHEDULED } ?: return null
            val at = row.scheduledAt ?: return null
            return ((at - nowMs + 999L) / 1000L).coerceAtLeast(1L).toInt()
        }
    }
