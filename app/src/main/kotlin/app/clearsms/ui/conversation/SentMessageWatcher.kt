package app.clearsms.ui.conversation

import app.clearsms.data.db.DeliveryStatus
import app.clearsms.data.db.MessageDao
import app.clearsms.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the outcome of a dispatched message by watching its PERSISTED
 * [DeliveryStatus]: [app.clearsms.sms.SmsSender] / [app.clearsms.mms.MmsSender]
 * write the row at [DeliveryStatus.SENDING] and
 * [app.clearsms.receiver.SmsSentReceiver] / [app.clearsms.receiver.MmsSentReceiver]
 * record the platform's reports against it, so observing the row is
 * observing the truth (no provider polling).
 */
@Singleton
class SentMessageWatcher
    @Inject
    constructor(
        private val messageDao: MessageDao,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        /**
         * Suspends until the send resolves or [windowMs] passes:
         * [SendStatus.FAILED] as soon as a failure is recorded,
         * [SendStatus.SENT] on a sent or delivery report.
         *
         * A silent window is transport-specific ([OutgoingSendPolicy]):
         * - SMS: the radio reports failures within a couple of seconds, so
         *   silence means the carrier accepted the message - the row is
         *   promoted to SENT (compare-and-set, so a report landing right now
         *   wins) and [SendStatus.SENT] is returned. Honesty-by-absence, not
         *   proof of delivery.
         * - MMS: the platform's single result may take minutes, so silence
         *   proves nothing. The row is left at SENDING - NEVER promoted here -
         *   and [SendStatus.SENDING] is returned; callers wait for the real
         *   result with [awaitResult].
         */
        suspend fun await(
            messageId: Long,
            windowMs: Long = RESULT_WINDOW_MS,
        ): SendStatus =
            withContext(ioDispatcher) {
                val resolved = withTimeoutOrNull(windowMs) { terminalStatus(messageId) }
                when (resolved) {
                    DeliveryStatus.FAILED -> SendStatus.FAILED
                    DeliveryStatus.SENT, DeliveryStatus.DELIVERED -> SendStatus.SENT
                    // Window elapsed with no report recorded.
                    DeliveryStatus.SENDING, DeliveryStatus.SCHEDULED, null -> {
                        val transport =
                            messageDao.getById(messageId)?.let(MessageDetails::transportOf)
                                ?: MessageDetails.Transport.SMS
                        val closeAs =
                            OutgoingSendPolicy.afterSilentWindow(transport, DeliveryStatus.SENDING, resultRecorded = false)
                        if (closeAs == DeliveryStatus.SENT) {
                            messageDao.promoteDeliveryStatus(messageId, DeliveryStatus.SENDING, DeliveryStatus.SENT)
                            SendStatus.SENT
                        } else {
                            SendStatus.SENDING
                        }
                    }
                }
            }

        /**
         * Suspends, with no time limit, until a REAL result is recorded on
         * the row - the MMS path after [await] reported [SendStatus.SENDING].
         * Never writes: the receiver is the only thing that ends the wait.
         */
        suspend fun awaitResult(messageId: Long): SendStatus =
            withContext(ioDispatcher) {
                when (terminalStatus(messageId)) {
                    DeliveryStatus.FAILED -> SendStatus.FAILED
                    else -> SendStatus.SENT
                }
            }

        private suspend fun terminalStatus(messageId: Long): DeliveryStatus =
            messageDao
                .observeDeliveryStatus(messageId)
                .filterNotNull()
                .first { it != DeliveryStatus.SENDING }

        companion object {
            /**
             * How long a dispatch is watched for a report before the send is
             * either called good (SMS) or handed to the long MMS wait. Sent
             * reports normally arrive well under 2 s; the window is generous
             * without stalling the confirmation unreasonably.
             */
            const val RESULT_WINDOW_MS = 4_000L
        }
    }
