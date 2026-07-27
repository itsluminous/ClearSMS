package app.clearsms.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import app.clearsms.data.db.DeliveryStatus
import app.clearsms.data.db.MessageDao
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.repository.SenderNormalizer
import app.clearsms.di.IoDispatcher
import app.clearsms.domain.model.Category
import app.clearsms.receiver.SmsSentReceiver
import app.clearsms.ui.common.UiPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends SMS messages via [SmsManager] and persists the outgoing message to
 * both the local Room database and the system SMS provider.
 *
 * The Room row is written with `isOutgoing = true` and
 * [DeliveryStatus.SENDING], plus the provider row's id as `systemSmsId`, so
 * [SmsSentReceiver] can record the radio's sent / delivery reports against
 * it — status survives restarts instead of living in screen state. Delivery
 * report requests are gated on the user's delivery-reports setting.
 *
 * Long bodies are divided into parts and sent as one multipart message; the
 * report [PendingIntent]s are attached to the last part only.
 */
@Singleton
class SmsSender
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val messageDao: MessageDao,
        private val telephonyWriter: TelephonyWriter,
        private val uiPrefs: UiPrefs,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        /**
         * Sends [body] to [destination] and records the message locally.
         *
         * @return the Room row id of the persisted outgoing message. A
         *   dispatch failure after persistence marks the row FAILED and
         *   still returns the id (the bubble shows the failure).
         */
        suspend fun send(
            destination: String,
            body: String,
        ): Long =
            withContext(ioDispatcher) {
                val timestamp = System.currentTimeMillis()
                val providerUri = telephonyWriter.writeSent(destination, body, timestamp)
                val systemSmsId = providerUri?.lastPathSegment?.toLongOrNull()
                val messageId = persistToRoom(destination, body, timestamp, systemSmsId)
                dispatch(messageId, destination, body, providerUri?.toString())
                messageId
            }

        /**
         * Re-dispatches a previously failed outgoing message on the SAME Room
         * row: the bubble keeps its place (original timestamp) and flips back
         * to Sending. The provider gets a fresh sent row (the old one, marked
         * failed, is removed) and `systemSmsId` is repointed at it.
         */
        suspend fun resend(messageId: Long) {
            withContext(ioDispatcher) {
                val message = messageDao.getById(messageId) ?: return@withContext
                if (!message.isOutgoing) return@withContext
                message.systemSmsId?.let { telephonyWriter.deleteBySystemIds(listOf(it)) }
                val providerUri = telephonyWriter.writeSent(message.sender, message.body, message.timestamp)
                messageDao.resetForResend(messageId, providerUri?.lastPathSegment?.toLongOrNull())
                dispatch(messageId, message.sender, message.body, providerUri?.toString())
            }
        }

        /**
         * Hands the message to the radio. A synchronous throw is recorded as
         * FAILED on the row and swallowed — the persisted status IS the
         * failure signal callers observe (via [MessageDao.observeDeliveryStatus]).
         */
        private suspend fun dispatch(
            messageId: Long,
            destination: String,
            body: String,
            providerUri: String?,
        ) {
            try {
                val smsManager = smsManager()
                val parts = smsManager.divideMessage(body)
                val sentIntent =
                    resultPendingIntent(SmsSentReceiver.ACTION_SMS_SENT, destination, providerUri)
                // No delivery-report request when the setting is off: the
                // message then honestly caps at Sent, never shows Delivered.
                val deliveredIntent =
                    if (uiPrefs.deliveryReports.first()) {
                        resultPendingIntent(SmsSentReceiver.ACTION_SMS_DELIVERED, destination, providerUri)
                    } else {
                        null
                    }
                // Attach the report intents to the last part only so a single
                // multipart message produces exactly one sent/delivery report.
                val sentIntents = arrayOfNulls<PendingIntent>(parts.size).also { it[parts.size - 1] = sentIntent }
                val deliveredIntents =
                    arrayOfNulls<PendingIntent>(parts.size).also { it[parts.size - 1] = deliveredIntent }
                smsManager.sendMultipartTextMessage(
                    destination,
                    null,
                    parts,
                    ArrayList(sentIntents.toList()),
                    ArrayList(deliveredIntents.toList()),
                )
            } catch (_: Exception) {
                messageDao.setDeliveryStatus(messageId, DeliveryStatus.FAILED)
            }
        }

        private suspend fun persistToRoom(
            destination: String,
            body: String,
            timestampMs: Long,
            systemSmsId: Long?,
        ): Long {
            val normalized = SenderNormalizer.normalize(destination)
            val threadId = messageDao.threadIdFor(normalized) ?: ((messageDao.maxThreadId() ?: 0L) + 1L)
            return messageDao.insert(
                MessageEntity(
                    threadId = threadId,
                    sender = destination,
                    normalizedSender = normalized,
                    body = body,
                    timestamp = timestampMs,
                    isRead = true,
                    category = Category.PERSONAL,
                    systemSmsId = systemSmsId,
                    isOutgoing = true,
                    deliveryStatus = DeliveryStatus.SENDING,
                ),
            )
        }

        private fun resultPendingIntent(
            action: String,
            destination: String,
            providerUri: String?,
        ): PendingIntent {
            val intent =
                Intent(context, SmsSentReceiver::class.java)
                    .setAction(action)
                    .putExtra(SmsSentReceiver.EXTRA_DESTINATION, destination)
                    .putExtra(SmsSentReceiver.EXTRA_PROVIDER_URI, providerUri)
            return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE.getAndIncrement(),
                intent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun smsManager(): SmsManager =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requireNotNull(context.getSystemService(SmsManager::class.java))
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

        private companion object {
            val REQUEST_CODE =
                java.util.concurrent.atomic
                    .AtomicInteger(1000)
        }
    }
