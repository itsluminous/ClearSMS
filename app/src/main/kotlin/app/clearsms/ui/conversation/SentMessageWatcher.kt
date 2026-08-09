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
 * Resolves the outcome of a dispatched SMS by watching the message's
 * PERSISTED [DeliveryStatus]: [app.clearsms.sms.SmsSender] writes the row at
 * [DeliveryStatus.SENDING] and [app.clearsms.receiver.SmsSentReceiver]
 * records the radio's sent / delivery reports against it, so observing the
 * row is observing the truth (no provider polling).
 */
@Singleton
class SentMessageWatcher
    @Inject
    constructor(
        private val messageDao: MessageDao,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        /**
         * Suspends until the send resolves: [SendStatus.FAILED] as soon as a
         * failure is recorded, [SendStatus.SENT] on a sent or delivery
         * report, otherwise [SendStatus.SENT] once [RESULT_WINDOW_MS] passes
         * without one (the radio reports failures within a couple of
         * seconds, so a quiet window means the carrier accepted the message
         * - honesty-by-absence, not proof of delivery; the row is then also
         * promoted to SENT so the bubble stops saying "Sending").
         */
        suspend fun await(
            messageId: Long,
            windowMs: Long = RESULT_WINDOW_MS,
        ): SendStatus =
            withContext(ioDispatcher) {
                val resolved =
                    withTimeoutOrNull(windowMs) {
                        messageDao
                            .observeDeliveryStatus(messageId)
                            .filterNotNull()
                            .first { it != DeliveryStatus.SENDING }
                    }
                when (resolved) {
                    DeliveryStatus.FAILED -> SendStatus.FAILED
                    DeliveryStatus.SENT, DeliveryStatus.DELIVERED -> SendStatus.SENT
                    // Window elapsed with no report recorded: call it sent.
                    // Compare-and-set so a report landing right now wins.
                    DeliveryStatus.SENDING, DeliveryStatus.SCHEDULED, null -> {
                        messageDao.promoteDeliveryStatus(messageId, DeliveryStatus.SENDING, DeliveryStatus.SENT)
                        SendStatus.SENT
                    }
                }
            }

        companion object {
            /**
             * How long a dispatch is watched for a radio failure report
             * before the send is called good. Sent reports normally arrive
             * well under 2 s; the window is generous without stalling the
             * confirmation unreasonably.
             */
            const val RESULT_WINDOW_MS = 4_000L
        }
    }
