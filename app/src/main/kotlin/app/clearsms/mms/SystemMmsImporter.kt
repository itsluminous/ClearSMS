package app.clearsms.mms

import app.clearsms.data.repository.MessageRepository
import app.clearsms.data.repository.MmsAttachmentDraft
import app.clearsms.di.IoDispatcher
import app.clearsms.diagnostics.Diag
import app.clearsms.diagnostics.DiagField.Companion.count
import app.clearsms.work.SyncCheckpointStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bulk-imports received MMS history from the system MMS provider
 * (`content://mms`) into the local database - the other half of the
 * catch-up import, alongside [app.clearsms.sms.SystemSmsImporter].
 *
 * Why this exists (issue #94): an MMS only reaches the app live, as a WAP
 * push, while Clear SMS holds the default-SMS role. Every MMS that arrived
 * before the install - or while another app was default - lives only in the
 * shared provider, so without this import it was invisible forever, even
 * though other messaging apps on the same device still showed it. Where a
 * carrier has since switched MMS off entirely (Germany, July 2026) the live
 * path can never fire again, which made *every* MMS on such a device
 * permanently unreachable.
 *
 * Shares the SMS import's durability properties:
 * - **Resumable** - rows are read in `_id` order in pages of [PAGE_SIZE];
 *   the [SyncCheckpointStore] MMS checkpoint advances after each page.
 * - **Idempotent** - every row carries its provider `_id` in
 *   `messages.systemMmsId`, guarded by a unique index, so redoing a page
 *   cannot duplicate a message.
 *
 * Unlike the SMS import this is deliberately NOT parallelised: the cost per
 * message is provider I/O (an addr query, a parts query and a stream read
 * per attachment), not CPU, and MMS counts are small - tens or hundreds,
 * against the tens of thousands of SMS the other importer handles.
 */
@Singleton
class SystemMmsImporter
    @Inject
    constructor(
        private val source: SystemMmsSource,
        private val repository: MessageRepository,
        private val attachmentStore: AttachmentStore,
        private val checkpointStore: SyncCheckpointStore,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        /** Outcome of one import run. */
        data class Result(
            val imported: Int,
            val skipped: Int,
        )

        /**
         * Imports every received MMS not yet stored, resuming from the
         * durable checkpoint.
         *
         * @param onProgress called after each committed page with
         *   (imported so far, total known) - total is 0 when the provider
         *   would not report a count.
         */
        suspend fun importAll(onProgress: suspend (Int, Int) -> Unit = { _, _ -> }): Result =
            withContext(ioDispatcher) {
                val total = source.inboxCount() ?: 0
                var imported = 0
                var skipped = 0
                var lastId = checkpointStore.lastSystemMmsId()
                Diag.i(TAG, "mms import starting", count("providerInbox", total.toLong()), count("fromId", lastId))
                while (true) {
                    val page = source.inboxAfter(afterId = lastId, limit = PAGE_SIZE)
                    if (page.isEmpty()) break
                    for (message in page) {
                        if (store(message)) imported++ else skipped++
                    }
                    // The checkpoint advances past the WHOLE page, including
                    // messages that were skipped as empty or already stored:
                    // they would be skipped again on every future run, and
                    // re-reading them forever would make the import never
                    // settle.
                    lastId = page.last().systemMmsId
                    checkpointStore.setLastSystemMmsId(lastId)
                    onProgress(imported, total)
                    if (page.size < PAGE_SIZE) break
                }
                Diag.i(
                    TAG,
                    "mms import finished",
                    count("imported", imported.toLong()),
                    count("skipped", skipped.toLong()),
                )
                Result(imported = imported, skipped = skipped)
            }

        /**
         * Stores one provider MMS. @return true when a new row was written.
         *
         * Order matters: the row is inserted FIRST, because its id names the
         * attachment directory and because the insert is what detects an
         * already-imported message. Only then are the bytes written to disk,
         * so a duplicate page can never leave orphaned files behind.
         */
        private suspend fun store(message: ProviderMms): Boolean {
            // An MMS with neither text nor attachments carries nothing a
            // user could read - a bare delivery record. Importing it would
            // add an empty bubble to the conversation.
            if (!message.hasContent()) return false
            // The sender is the one field the app cannot do without: it
            // decides the thread. A provider row whose addr table has no
            // FROM entry cannot be placed, so it is left alone rather than
            // filed under a fabricated sender.
            val sender = message.sender?.takeIf { it.isNotBlank() } ?: return false
            val drafts =
                message.attachments.mapIndexed { index, part ->
                    MmsAttachmentDraft(
                        mimeType = part.mimeType,
                        fileName = AttachmentStore.fileNameFor(index, part),
                        sizeBytes = part.data.size.toLong(),
                    )
                }
            val stored =
                repository.insertImportedMms(
                    systemMmsId = message.systemMmsId,
                    sender = sender,
                    body = message.body,
                    timestampMs = message.timestampMs,
                    dateSentMs = message.sentAtMs,
                    isRead = message.isRead,
                    providerThreadId = message.providerThreadId,
                    subscriptionId = message.subscriptionId,
                    recipients = message.recipients,
                    attachments = drafts,
                ) ?: return false
            if (message.attachments.isNotEmpty()) {
                try {
                    attachmentStore.write(stored.id, message.attachments)
                } catch (e: Exception) {
                    // The row is already committed and renders its text; a
                    // failed file write costs the image, not the message.
                    Diag.e(TAG, "imported mms attachments unwritable", e, *message.diagFields())
                }
            }
            Diag.d(TAG, "imported mms", *message.diagFields())
            return true
        }

        private companion object {
            const val TAG = "SystemMmsImporter"

            /**
             * Smaller than the SMS importer's page: each MMS costs several
             * provider round-trips and holds its attachment bytes in memory
             * while the page is processed.
             */
            const val PAGE_SIZE = 25
        }
    }
