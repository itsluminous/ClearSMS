package app.clearsms.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import app.clearsms.data.db.MessageDao
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.repository.SenderNormalizer
import app.clearsms.di.IoDispatcher
import app.clearsms.domain.model.Category
import app.clearsms.receiver.SmsSentReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends SMS messages via [SmsManager] and persists the outgoing message to
 * both the local Room database and the system SMS provider.
 *
 * Long bodies are divided into parts and sent as one multipart message. Sent
 * and delivery reports are routed to [SmsSentReceiver] via [PendingIntent]s
 * carrying the system provider row uri, so the provider row can be updated
 * with the final status.
 */
@Singleton
class SmsSender
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val messageDao: MessageDao,
        private val telephonyWriter: TelephonyWriter,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        /** Sends [body] to [destination] and records the message locally. */
        suspend fun send(
            destination: String,
            body: String,
        ) {
            withContext(ioDispatcher) {
                val timestamp = System.currentTimeMillis()
                val providerUri = telephonyWriter.writeSent(destination, body, timestamp)
                persistToRoom(destination, body, timestamp)

                val smsManager = smsManager()
                val parts = smsManager.divideMessage(body)
                val sentIntent =
                    resultPendingIntent(SmsSentReceiver.ACTION_SMS_SENT, destination, providerUri?.toString())
                val deliveredIntent =
                    resultPendingIntent(SmsSentReceiver.ACTION_SMS_DELIVERED, destination, providerUri?.toString())
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
            }
        }

        private suspend fun persistToRoom(
            destination: String,
            body: String,
            timestampMs: Long,
        ) {
            val normalized = SenderNormalizer.normalize(destination)
            val threadId = messageDao.threadIdFor(normalized) ?: ((messageDao.maxThreadId() ?: 0L) + 1L)
            messageDao.insert(
                MessageEntity(
                    threadId = threadId,
                    sender = destination,
                    normalizedSender = normalized,
                    body = body,
                    timestamp = timestampMs,
                    isRead = true,
                    category = Category.PERSONAL,
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
