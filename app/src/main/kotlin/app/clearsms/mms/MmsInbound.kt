package app.clearsms.mms

import app.clearsms.data.repository.MessageRepository
import app.clearsms.diagnostics.Diag
import app.clearsms.diagnostics.DiagField.Companion.count
import app.clearsms.diagnostics.DiagField.Companion.flag
import app.clearsms.diagnostics.DiagField.Companion.id
import app.clearsms.diagnostics.DiagField.Companion.mime
import app.clearsms.notification.IncomingMessageRouter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The incoming-MMS pipeline behind the two receivers:
 *
 * 1. [onNotification] - a WAP push (`m-notification-ind`) arrives: parse
 *    it, store the PENDING row, start the carrier download.
 * 2. [onDownloadResult] - the platform reports the download outcome:
 *    parse the staged `m-retrieve-conf`, store attachments and the text
 *    body, and notify through the SAME router SMS uses; or retry once,
 *    then leave a visible FAILED row the user can tap to retry.
 *
 * Everything here is defensive: junk PDUs are dropped (notification) or
 * marked failed (retrieve) - never thrown.
 */
@Singleton
class MmsInbound
    @Inject
    constructor(
        private val messageRepository: MessageRepository,
        private val downloader: MmsDownloader,
        private val attachmentStore: AttachmentStore,
        private val incomingMessageRouter: IncomingMessageRouter,
    ) {
        /**
         * Handles an `m-notification-ind` push. @return the stored pending
         * row's id, or null when the PDU did not parse (dropped).
         */
        suspend fun onNotification(
            pdu: ByteArray,
            timestampMs: Long = System.currentTimeMillis(),
        ): Long? {
            val notification = MmsNotificationParser.parse(pdu, nowMs = timestampMs) ?: return null
            // The notification names the sender (or defers it with the
            // insert-address token) and says how big the content is; the
            // size is what a "download keeps failing" report needs.
            Diag.i(
                TAG,
                "mms notification parsed",
                flag("senderKnown", !notification.sender.isNullOrEmpty()),
                count("declaredBytes", notification.messageSizeBytes ?: -1L),
            )
            val entity =
                messageRepository.insertMmsNotification(
                    // The insert-address token leaves the sender to the
                    // retrieve-conf; an empty sender is re-attributed there.
                    sender = notification.sender.orEmpty(),
                    timestampMs = timestampMs,
                    transactionId = notification.transactionId,
                    contentLocation = notification.contentLocation,
                )
            downloader.download(entity.id, notification.contentLocation, attempt = 0)
            return entity.id
        }

        /** Handles the platform's download result for [messageId]. */
        suspend fun onDownloadResult(
            messageId: Long,
            succeeded: Boolean,
            attempt: Int,
            contentLocation: suspend () -> String?,
        ) {
            if (succeeded) {
                complete(messageId)
                return
            }
            val location = contentLocation()
            val retrying = attempt < MAX_ATTEMPTS - 1 && location != null
            Diag.w(
                TAG,
                "mms download attempt failed",
                null,
                id("message", messageId),
                count("attempt", attempt),
                flag("locationKnown", location != null),
                flag("retrying", retrying),
            )
            if (retrying) {
                // One transient-failure retry, then give up visibly.
                downloader.download(messageId, location!!, attempt = attempt + 1)
            } else {
                messageRepository.markMmsFailed(messageId)
            }
        }

        /** User tapped a FAILED row: flip it back to PENDING and re-download. */
        suspend fun retry(messageId: Long) {
            val row = messageRepository.markMmsPendingForRetry(messageId)
            if (row == null) {
                Diag.w(TAG, "mms retry for missing message", null, id("message", messageId))
                return
            }
            val location = row.mmsContentLocation
            if (location == null) {
                // Without the content location there is nothing to fetch:
                // the row stays PENDING forever - a user-visible dead end.
                Diag.w(TAG, "mms retry without content location", null, id("message", messageId))
                return
            }
            // A retry burns the single automatic retry too: attempt starts
            // at the last slot so the next failure goes straight to FAILED.
            downloader.download(messageId, location, attempt = MAX_ATTEMPTS - 1)
        }

        private suspend fun complete(messageId: Long) {
            val staged = attachmentStore.stagingFile(messageId)
            val parsed =
                try {
                    staged.takeIf { it.exists() }?.readBytes()?.let(MmsRetrieveConfParser::parse)
                } catch (e: Exception) {
                    Diag.e(TAG, "staged mms pdu unreadable", e, id("message", messageId), count("stagedBytes", staged.length()))
                    null
                }
            if (parsed == null) {
                // "Downloaded" but unreadable is a failure the user can
                // retry. A zero-length staging file means the platform
                // reported success without writing anything.
                Diag.w(
                    TAG,
                    "mms retrieve-conf unusable",
                    null,
                    id("message", messageId),
                    flag("stagedFileExists", staged.exists()),
                    count("stagedBytes", staged.length()),
                )
                staged.delete()
                messageRepository.markMmsFailed(messageId)
                return
            }
            parsed.attachments.forEachIndexed { index, part ->
                Diag.d(
                    TAG,
                    "mms attachment received",
                    id("message", messageId),
                    count("index", index),
                    mime(part.mimeType),
                    count("bytes", part.data.size),
                )
            }
            // How many parties the message was addressed to (group MMS is
            // attributed to its sender today) - a count, never the parties.
            val groupSize = parsed.recipients.size
            Diag.i(
                TAG,
                "mms retrieve-conf parsed",
                id("message", messageId),
                count("attachments", parsed.attachments.size),
                count("attachmentBytes", parsed.attachments.sumOf { it.data.size.toLong() }),
                flag("senderKnown", !parsed.sender.isNullOrEmpty()),
                count("groupSize", groupSize),
            )
            val drafts = attachmentStore.write(messageId, parsed.attachments)
            val entity =
                messageRepository.completeMmsDownload(
                    messageId = messageId,
                    sender = parsed.sender,
                    body = parsed.text,
                    recipients = parsed.recipients,
                    attachments = drafts,
                )
            staged.delete()
            // An MMS notifies exactly like an SMS: same router, same gates
            // (blocked senders silent, categories decide the channel).
            if (entity != null) incomingMessageRouter.route(entity)
        }

        companion object {
            private const val TAG = "MmsInbound"

            /** Total download attempts: the initial one plus one retry. */
            const val MAX_ATTEMPTS = 2
        }
    }
