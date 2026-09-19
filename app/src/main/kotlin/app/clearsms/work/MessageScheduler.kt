package app.clearsms.work

import app.clearsms.data.db.DeliveryStatus
import app.clearsms.data.db.MessageDao
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.repository.SenderNormalizer
import app.clearsms.di.IoDispatcher
import app.clearsms.domain.model.Category
import app.clearsms.sms.AccentFold
import app.clearsms.sms.SmsSender
import app.clearsms.ui.common.UiPrefs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Message scheduling: a scheduled message is a normal outgoing Room row at
 * [DeliveryStatus.SCHEDULED] with a `scheduledAt` fire time - visible
 * in-thread as a "scheduled" bubble, durable across restarts, and NOT yet
 * written to the system SMS provider (nothing was sent; other SMS apps must
 * not see a phantom sent message).
 *
 * Firing goes through [SmsSender.sendScheduled]: provider row, SENDING, the
 * SIM chosen at scheduling time, multipart, the normal report lifecycle -
 * a failed eventual send lands in the existing FAILED + retry flow.
 * Alarms do not survive reboots or clock changes; [rearmAll] re-registers
 * every pending schedule and fires overdue ones immediately.
 */
@Singleton
class MessageScheduler
    @Inject
    constructor(
        private val messageDao: MessageDao,
        private val smsSender: SmsSender,
        private val alarms: ScheduledSendAlarms,
        private val uiPrefs: UiPrefs,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        /**
         * Persists [body] as a scheduled message to [destination] and arms
         * its alarm. The row's timestamp mirrors [scheduledAtMs] so the
         * bubble sits at its future position in the thread.
         *
         * @return the Room row id of the scheduled message.
         */
        suspend fun schedule(
            destination: String,
            body: String,
            subscriptionId: Int?,
            scheduledAtMs: Long,
        ): Long =
            withContext(ioDispatcher) {
                // Same opt-in auto accent-fold as SmsSender.send, applied at
                // scheduling time because the persisted body IS what
                // sendScheduled later dispatches - the scheduled bubble must
                // show exactly what will go on the wire.
                val sendBody = if (uiPrefs.stripAccents.first()) AccentFold.foldIfItSaves(body) else body
                val normalized = SenderNormalizer.normalize(destination)
                val threadId = messageDao.threadIdFor(normalized) ?: ((messageDao.maxThreadId() ?: 0L) + 1L)
                val messageId =
                    messageDao.insert(
                        MessageEntity(
                            threadId = threadId,
                            sender = destination,
                            normalizedSender = normalized,
                            body = sendBody,
                            timestamp = scheduledAtMs,
                            isRead = true,
                            category = Category.PERSONAL,
                            isOutgoing = true,
                            deliveryStatus = DeliveryStatus.SCHEDULED,
                            subscriptionId = subscriptionId,
                            scheduledAt = scheduledAtMs,
                        ),
                    )
                alarms.arm(messageId, scheduledAtMs)
                messageId
            }

        /** Moves a pending schedule to [scheduledAtMs] and re-arms its alarm. */
        suspend fun reschedule(
            messageId: Long,
            scheduledAtMs: Long,
        ) {
            withContext(ioDispatcher) {
                if (messageDao.updateScheduledTime(messageId, scheduledAtMs) > 0) {
                    alarms.arm(messageId, scheduledAtMs)
                }
            }
        }

        /**
         * Cancels a pending schedule: alarm cleared, row deleted outright -
         * nothing was ever sent, so there is no history worth a recycle-bin
         * trip (matching the user's mental model of "cancel").
         *
         * The delete is [MessageDao.deleteIfScheduled] - a compare-and-set
         * on SCHEDULED, NOT a read-then-delete: a cancel racing the alarm's
         * fire must have exactly one winner. If the fire's
         * `markDispatchedFromSchedule` claims first, this delete matches
         * nothing and the sent row (now SENDING) is left alone; if this
         * delete wins, the fire's claim matches nothing and it rolls back
         * its provider row without touching the radio.
         */
        suspend fun cancel(messageId: Long) {
            withContext(ioDispatcher) {
                alarms.cancel(messageId)
                messageDao.deleteIfScheduled(messageId)
            }
        }

        /**
         * Cancels a DELAYED send (GitHub #40) - same exactly-once contest
         * as [cancel], but the caller needs the message body back so it can
         * be restored into the composer for editing.
         *
         * @return the persisted body when THIS cancel won (the message will
         *   never be sent), or null when it lost - the row is gone or the
         *   fire already claimed it, so the message is (being) sent and
         *   restoring its text would duplicate it. The body is read before
         *   the compare-and-set delete, which is safe because a message
         *   body is immutable once persisted.
         */
        suspend fun cancelDelayed(messageId: Long): String? =
            withContext(ioDispatcher) {
                alarms.cancel(messageId)
                val body =
                    messageDao
                        .getById(messageId)
                        ?.takeIf { it.deliveryStatus == DeliveryStatus.SCHEDULED }
                        ?.body ?: return@withContext null
                if (messageDao.deleteIfScheduled(messageId) > 0) body else null
            }

        /** Fires a pending schedule right now (the bubble's "Send now"). */
        suspend fun sendNow(messageId: Long) {
            withContext(ioDispatcher) {
                alarms.cancel(messageId)
                smsSender.sendScheduled(messageId)
            }
        }

        /** Alarm target: dispatches the message when its time arrives. */
        suspend fun fire(messageId: Long) {
            withContext(ioDispatcher) {
                smsSender.sendScheduled(messageId)
            }
        }

        /**
         * Re-registers every pending schedule (after boot, a time-set or a
         * timezone change). A schedule whose moment passed while the device
         * was off fires immediately; future ones get fresh alarms.
         */
        suspend fun rearmAll(nowMs: Long = System.currentTimeMillis()) {
            withContext(ioDispatcher) {
                for (message in messageDao.scheduledMessages()) {
                    val at = message.scheduledAt ?: continue
                    if (at <= nowMs) {
                        smsSender.sendScheduled(message.id)
                    } else {
                        alarms.arm(message.id, at)
                    }
                }
            }
        }

        /** Whether the next schedule will fire exactly (drives the picker hint). */
        fun exactAlarmsAllowed(): Boolean = alarms.exactAlarmsAllowed()
    }
