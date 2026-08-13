package app.clearsms.mms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import app.clearsms.data.db.AttachmentDao
import app.clearsms.data.db.AttachmentEntity
import app.clearsms.data.db.DeliveryStatus
import app.clearsms.data.db.MessageDao
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.repository.SenderNormalizer
import app.clearsms.di.IoDispatcher
import app.clearsms.domain.model.Category
import app.clearsms.receiver.MmsSentReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends MMS messages: persists the outgoing Room row (SENDING) and its
 * attachment rows/files, encodes a clean-room `m-send-req`, stages the
 * PDU behind the FileProvider and hands it to the platform MMS service
 * via [MmsGateway] with the chosen SIM's subscription.
 *
 * The send lifecycle is the SMS one: SENDING -> SENT (RESULT_OK in
 * [MmsSentReceiver]) or FAILED (any error, or a synchronous dispatch
 * throw). There is deliberately NO DELIVERED state for MMS -
 * delivery-report support (X-Mms-Delivery-Report) is out of scope this
 * wave, so the bubble honestly caps at Sent. Unlike SMS, no system
 * provider row is written: mirroring an MMS into `content://mms` means
 * hand-writing pdu/part/addr tables, which is out of scope - the message
 * lives in the app's own store (the same place received MMS live).
 */
@Singleton
class MmsSender
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val messageDao: MessageDao,
        private val attachmentDao: AttachmentDao,
        private val attachmentStore: AttachmentStore,
        private val stager: OutgoingAttachmentStager,
        private val gateway: MmsGateway,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        /**
         * Sends [body] plus [attachments] to [destination] and records the
         * message locally. Staged files are consumed: their bytes move into
         * the message's attachment directory and the staged copies are
         * deleted.
         *
         * @return the Room row id. A dispatch failure after persistence
         *   marks the row FAILED and still returns the id (the bubble
         *   shows the failure and offers Retry).
         */
        suspend fun send(
            destination: String,
            body: String,
            attachments: List<StagedAttachment>,
            subscriptionId: Int? = null,
        ): Long =
            withContext(ioDispatcher) {
                val timestamp = System.currentTimeMillis()
                val parts =
                    attachments.map { staged ->
                        MmsPart(mimeType = staged.mimeType, fileName = staged.displayName, data = staged.file.readBytes())
                    }
                val messageId = persistToRoom(destination, body, timestamp, subscriptionId, parts)
                val drafts = attachmentStore.write(messageId, parts)
                attachmentDao.insertAll(
                    drafts.map {
                        AttachmentEntity(
                            messageId = messageId,
                            mimeType = it.mimeType,
                            fileName = it.fileName,
                            sizeBytes = it.sizeBytes,
                        )
                    },
                )
                attachments.forEach(stager::discard)
                dispatch(messageId, destination, body, parts, subscriptionId, timestamp)
                messageId
            }

        /**
         * Re-dispatches a previously failed outgoing MMS on the SAME Room
         * row: the PDU is re-encoded from the stored attachment files and
         * body, the bubble flips back to Sending in place, and the row's
         * recorded SIM is reused - a retry never silently switches SIMs.
         */
        suspend fun resend(messageId: Long) {
            withContext(ioDispatcher) {
                val message = messageDao.getById(messageId) ?: return@withContext
                if (!message.isOutgoing) return@withContext
                val parts =
                    attachmentDao.forMessage(messageId).mapNotNull { row ->
                        val file = attachmentStore.fileFor(messageId, row.fileName)
                        if (!file.exists()) return@mapNotNull null
                        // Stored names carry the collision-proof index
                        // prefix; the wire name is the human part.
                        MmsPart(row.mimeType, row.fileName.substringAfter('-'), file.readBytes())
                    }
                messageDao.resetForResend(messageId, message.systemSmsId)
                dispatch(messageId, message.sender, message.body, parts, message.subscriptionId, message.timestamp)
            }
        }

        /**
         * Encodes and hands the PDU to the platform. A synchronous throw
         * is recorded as FAILED on the row and swallowed - the persisted
         * status IS the failure signal callers observe.
         */
        private suspend fun dispatch(
            messageId: Long,
            destination: String,
            body: String,
            parts: List<MmsPart>,
            subscriptionId: Int?,
            timestampMs: Long,
        ) {
            try {
                val pdu =
                    MmsSendReqEncoder.encode(
                        MmsSendReq(
                            to = destination,
                            transactionId = "clearsms-${UUID.randomUUID()}",
                            text = body,
                            attachments = parts,
                            dateSeconds = timestampMs / 1000,
                        ),
                    )
                // The send stages its PDU in the same per-message staging
                // slot downloads use; an outgoing row never downloads, so
                // the file cannot collide.
                val staged = attachmentStore.stagingFile(messageId)
                staged.writeBytes(pdu)
                gateway.sendMultimediaMessage(subscriptionId, staged, sentIntent(messageId, destination))
            } catch (e: Exception) {
                // Content-free by convention: no body or address in logs.
                Log.e(TAG, "Failed to start MMS send", e)
                messageDao.setDeliveryStatus(messageId, DeliveryStatus.FAILED)
            }
        }

        private suspend fun persistToRoom(
            destination: String,
            body: String,
            timestampMs: Long,
            subscriptionId: Int?,
            parts: List<MmsPart>,
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
                    isOutgoing = true,
                    deliveryStatus = DeliveryStatus.SENDING,
                    subscriptionId = subscriptionId,
                    attachmentKinds = attachmentKinds(parts),
                ),
            )
        }

        /** "IMAGE", "FILE" or "IMAGE,FILE" - same denormalization received MMS use. */
        private fun attachmentKinds(parts: List<MmsPart>): String? {
            if (parts.isEmpty()) return null
            val kinds = mutableListOf<String>()
            if (parts.any { it.isImage }) kinds += "IMAGE"
            if (parts.any { !it.isImage }) kinds += "FILE"
            return kinds.joinToString(",")
        }

        private fun sentIntent(
            messageId: Long,
            destination: String,
        ): PendingIntent {
            val intent =
                Intent(context, MmsSentReceiver::class.java)
                    .setAction(MmsSentReceiver.ACTION_MMS_SENT)
                    .putExtra(MmsSentReceiver.EXTRA_MESSAGE_ID, messageId)
                    .putExtra(MmsSentReceiver.EXTRA_DESTINATION, destination)
            return PendingIntent.getBroadcast(
                context,
                // Unique per message so parallel sends never collide.
                messageId.toInt(),
                intent,
                // MUTABLE: the platform fills in the result code.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
        }

        private companion object {
            const val TAG = "MmsSender"
        }
    }
