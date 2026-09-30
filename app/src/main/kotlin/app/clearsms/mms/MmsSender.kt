package app.clearsms.mms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.telephony.SubscriptionManager
import app.clearsms.data.db.AttachmentDao
import app.clearsms.data.db.AttachmentEntity
import app.clearsms.data.db.DeliveryStatus
import app.clearsms.data.db.MessageDao
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.repository.SenderNormalizer
import app.clearsms.di.IoDispatcher
import app.clearsms.diagnostics.Diag
import app.clearsms.diagnostics.DiagField.Companion.count
import app.clearsms.diagnostics.DiagField.Companion.flag
import app.clearsms.diagnostics.DiagField.Companion.id
import app.clearsms.diagnostics.DiagField.Companion.label
import app.clearsms.diagnostics.DiagField.Companion.mime
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
 * throw) - with one difference: the platform may sit on an MMS for
 * minutes before reporting, so nothing but that result may move the row
 * off SENDING (no silent-window promotion; see
 * [app.clearsms.ui.conversation.OutgoingSendPolicy]). There is
 * deliberately NO DELIVERED state for MMS -
 * delivery-report support (X-Mms-Delivery-Report) is out of scope this
 * wave, so the bubble honestly caps at Sent. Unlike SMS, no system
 * provider row is written: mirroring an MMS into `content://mms` means
 * hand-writing pdu/part/addr tables, which is out of scope - the message
 * lives in the app's own store (the same place received MMS live).
 *
 * DIAGNOSTICS: every hand-over logs, through [Diag], what the platform is
 * being asked to send (part count, each attachment's byte size and MIME
 * type, PDU size) and what the radio looked like ([MmsSendConditions]);
 * a synchronous failure logs the exception class chain. Together with the
 * result code `MmsSentReceiver` logs, that tells "this app built a bad
 * MMS" apart from "the platform or carrier refused". NEVER logged: the
 * recipient, the text, an attachment's name or bytes.
 *
 * SIZE (issue #51): the platform MMS service refuses to read a PDU over
 * the carrier config `maxMessageSize` and answers `MMS_ERROR_IO_ERROR`
 * without touching the network. Attachments are compressed to fit that
 * limit when staged ([MmsSizeBudget]); the encoded PDU is checked against
 * the SAME limit here, for the SIM actually chosen, and one that still
 * exceeds it (the SIM was switched to a stricter carrier after attaching,
 * or an extreme body outgrew the envelope margin) is recorded FAILED as
 * [SendFailureReason.EXCEEDS_CARRIER_LIMIT] instead of being handed over
 * to fail at once.
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
        private val conditions: MmsSendConditionsProbe,
        private val carrierLimits: CarrierMmsLimits,
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
                dispatch(messageId, destination, body, parts, subscriptionId, timestamp, resend = false)
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
                val rows = attachmentDao.forMessage(messageId)
                val parts =
                    rows.mapNotNull { row ->
                        val file = attachmentStore.fileFor(messageId, row.fileName)
                        if (!file.exists()) return@mapNotNull null
                        // Stored names carry the collision-proof index
                        // prefix; the wire name is the human part.
                        MmsPart(row.mimeType, row.fileName.substringAfter('-'), file.readBytes())
                    }
                if (parts.size != rows.size) {
                    // The retry goes out with fewer attachments than the
                    // bubble shows - worth knowing when a user reports it.
                    Diag.w(
                        TAG,
                        "resend missing attachment files",
                        null,
                        id("message", messageId),
                        count("attachments", rows.size),
                        count("present", parts.size),
                    )
                }
                messageDao.resetForResend(messageId, message.systemSmsId)
                dispatch(messageId, message.sender, message.body, parts, message.subscriptionId, message.timestamp, resend = true)
            }
        }

        /**
         * Encodes and hands the PDU to the platform. A synchronous throw
         * is recorded as FAILED on the row (reason [SendFailureReason.DISPATCH_FAILED])
         * and swallowed - the persisted status IS the failure signal
         * callers observe.
         */
        private suspend fun dispatch(
            messageId: Long,
            destination: String,
            body: String,
            parts: List<MmsPart>,
            subscriptionId: Int?,
            timestampMs: Long,
            resend: Boolean,
        ) {
            logAttachments(messageId, parts)
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
                // Issue #51: an instant platform IO error means the PDU
                // was never read. Verify our half - the file is there and
                // complete - and say so, before the platform is asked.
                val check = StagedPduCheck.of(staged.exists(), staged.length(), pdu.size)
                val budget = MmsSizeBudget.forCarrier(carrierLimits.maxMessageSizeBytes(subscriptionId))
                logHandover(messageId, parts, pdu.size, check, budget, subscriptionId, resend)
                if (!budget.fits(pdu.size.toLong())) {
                    // The platform would answer IO_ERROR in milliseconds
                    // without asking the carrier; say why instead.
                    Diag.w(
                        TAG,
                        "pdu exceeds carrier limit before handover",
                        null,
                        id("message", messageId),
                        count("pduBytes", pdu.size),
                        count("carrierMaxBytes", budget.carrierMaxBytes ?: 0),
                        flag("limitKnown", budget.limitKnown),
                        count("limitBytes", budget.limitBytes),
                    )
                    staged.delete()
                    messageDao.markFailed(messageId, SendFailureReason.EXCEEDS_CARRIER_LIMIT.name)
                    return
                }
                if (!check.handoverSafe) {
                    Diag.e(
                        TAG,
                        "staged pdu incomplete before handover",
                        null,
                        id("message", messageId),
                        flag("exists", check.exists),
                        count("bytes", check.lengthBytes),
                        count("pduBytes", pdu.size),
                    )
                    messageDao.markFailed(messageId, SendFailureReason.DISPATCH_FAILED.name)
                    return
                }
                gateway.sendMultimediaMessage(messageId, subscriptionId, staged, sentIntent(messageId, destination, subscriptionId))
            } catch (e: Exception) {
                // The message never reached the platform: the app's own
                // failure (or a throwing SmsManager), distinct from every
                // result-code failure the receiver records.
                Diag.e(TAG, "mms handover failed", e, id("message", messageId), count("parts", parts.size), flag("resend", resend))
                messageDao.markFailed(messageId, SendFailureReason.DISPATCH_FAILED.name)
            }
        }

        /** One line per attachment: what will travel, by size and type - never by name or content. */
        private fun logAttachments(
            messageId: Long,
            parts: List<MmsPart>,
        ) {
            parts.forEachIndexed { index, part ->
                Diag.d(
                    TAG,
                    "mms attachment",
                    id("message", messageId),
                    count("index", index),
                    mime(part.mimeType),
                    count("bytes", part.data.size),
                )
            }
        }

        /**
         * The hand-over itself: payload shape plus the radio conditions at
         * that instant. `slot`/`dataSlot` are 1-based slots (0 = unknown);
         * `onDataSim` says whether the sending SIM is the phone's
         * mobile-data SIM - the one line that answers "did this MMS go out
         * on a SIM that can carry MMS on this phone?". `stagedBytes` is the
         * staged file's length as measured immediately before hand-over
         * (equal to `pduBytes` when all is well). `carrierMaxBytes` (0 =
         * unknown), `limitBytes` (what the platform will enforce) and
         * `fitsCarrierMax` say whether the PDU passes the platform's own
         * pre-network size check - the datum that decides issue #51.
         */
        private fun logHandover(
            messageId: Long,
            parts: List<MmsPart>,
            pduBytes: Int,
            staged: StagedPduCheck,
            budget: MmsSizeBudget,
            subscriptionId: Int?,
            resend: Boolean,
        ) {
            val radio = conditions.probe(subscriptionId)
            Diag.i(
                TAG,
                "mms handover",
                id("message", messageId),
                count("parts", parts.size),
                count("attachmentBytes", parts.sumOf { it.data.size.toLong() }),
                count("pduBytes", pduBytes),
                flag("stagedExists", staged.exists),
                count("stagedBytes", staged.lengthBytes),
                count("carrierMaxBytes", budget.carrierMaxBytes ?: 0),
                count("limitBytes", budget.limitBytes),
                flag("fitsCarrierMax", budget.fits(pduBytes.toLong())),
                flag("resend", resend),
                flag("defaultSubscription", subscriptionId == null),
                count("slot", radio.slot ?: 0),
                count("dataSlot", radio.dataSlot ?: 0),
                label("onDataSim", TriState.of(radio.onDataSim)),
                label("network", TriState.of(radio.networkConnected)),
                label("cellular", TriState.of(radio.cellular)),
                label("mobileData", TriState.of(radio.mobileDataEnabled)),
            )
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
            subscriptionId: Int?,
        ): PendingIntent {
            val intent =
                Intent(context, MmsSentReceiver::class.java)
                    .setAction(MmsSentReceiver.ACTION_MMS_SENT)
                    .putExtra(MmsSentReceiver.EXTRA_MESSAGE_ID, messageId)
                    .putExtra(MmsSentReceiver.EXTRA_DESTINATION, destination)
                    // So the failure report line can say whether the SIM the
                    // platform sent on was the data SIM, without a DB read.
                    .putExtra(
                        MmsSentReceiver.EXTRA_SUBSCRIPTION_ID,
                        subscriptionId ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID,
                    )
                    // So the failure report can say how long the platform
                    // took - an instant result means it never tried the
                    // network (issue #51). Monotonic clock: survives a
                    // wall-clock change between hand-over and result.
                    .putExtra(MmsSentReceiver.EXTRA_HANDOVER_ELAPSED_REALTIME_MS, SystemClock.elapsedRealtime())
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

/** A yes/no the platform may decline to answer, as a loggable label. */
enum class TriState {
    YES,
    NO,
    UNKNOWN,

    ;

    companion object {
        fun of(value: Boolean?): TriState =
            when (value) {
                true -> YES
                false -> NO
                null -> UNKNOWN
            }
    }
}
