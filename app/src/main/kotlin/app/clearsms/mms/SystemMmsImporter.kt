package app.clearsms.mms

import app.clearsms.data.db.MessageDao
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
 * Bulk-imports MMS history - received AND sent - from the system MMS
 * provider (`content://mms`) into the local database - the other half of
 * the catch-up import, alongside [app.clearsms.sms.SystemSmsImporter].
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
 * Why it reads the SENT box too (issue #112): an MMS sent from another app
 * is written to the provider by that app and nowhere else - Clear SMS only
 * stores the MMS it sends itself. The first import read the inbox only, so
 * a history moved over from another phone showed every received picture
 * and none of the user's replies. Which boxes are read, and why the rest
 * are not, is decided in [SystemMmsSource].
 *
 * Shares the SMS import's durability properties:
 * - **Resumable** - rows are read in `_id` order in pages of [PAGE_SIZE];
 *   the [SyncCheckpointStore] MMS checkpoint advances after each page.
 * - **Idempotent** - every row carries its provider `_id` in
 *   `messages.systemMmsId`, guarded by a unique index, so redoing a page
 *   cannot duplicate a message.
 * - **Scoped** - the checkpoint records which boxes its walk covered
 *   ([SyncCheckpointStore.mmsImportScope]). An install whose walk predates
 *   the sent-box import has a checkpoint already past every sent row, so
 *   the walk is restarted from 0 once; rows it already holds are skipped
 *   by id before any provider read, so the restart costs one light query
 *   per page, not a second copy of every attachment.
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
        private val messageDao: MessageDao,
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
         * Imports every received or sent MMS not yet stored, resuming from
         * the durable checkpoint - or from the start, once, when the
         * checkpoint's recorded scope predates the sent-box import.
         *
         * @param onProgress called after each committed page with
         *   (imported so far, total known) - total is 0 when the provider
         *   would not report a count.
         */
        suspend fun importAll(onProgress: suspend (Int, Int) -> Unit = { _, _ -> }): Result =
            withContext(ioDispatcher) {
                val total = source.importableCount() ?: 0
                var imported = 0
                var skipped = 0
                val scope = checkpointStore.mmsImportScope()
                if (scope < SyncCheckpointStore.MMS_SCOPE_CURRENT) {
                    // The walk behind the checkpoint never looked at sent
                    // rows (0.23.0), or there was no walk yet. Either way the
                    // checkpoint cannot be trusted to lie past every
                    // importable row: start over, under the current scope.
                    Diag.i(TAG, "mms import scope widened, walking from the start", count("scope", scope.toLong()))
                    checkpointStore.beginMmsWalk()
                }
                var lastId = checkpointStore.lastSystemMmsId()
                // Ids already stored are skipped before any provider read -
                // what keeps the widened re-walk from re-reading every
                // attachment the first walk already copied.
                val alreadyStored = messageDao.allSystemMmsIds().toHashSet()
                Diag.i(
                    TAG,
                    "mms import starting",
                    count("providerImportable", total.toLong()),
                    count("fromId", lastId),
                    count("alreadyStored", alreadyStored.size.toLong()),
                )
                while (true) {
                    val page = source.messagesAfter(afterId = lastId, limit = PAGE_SIZE, alreadyStored = alreadyStored)
                    val pageLastId = page.lastId ?: break
                    for (message in page.messages) {
                        if (store(message)) imported++ else skipped++
                    }
                    // The checkpoint advances past the WHOLE page, including
                    // messages that were skipped as empty or already stored:
                    // they would be skipped again on every future run, and
                    // re-reading them forever would make the import never
                    // settle.
                    lastId = pageLastId
                    checkpointStore.setLastSystemMmsId(lastId)
                    onProgress(imported, total)
                    if (page.rowCount < PAGE_SIZE) break
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
            // The other party is the one field the app cannot do without:
            // it decides the thread. A received row whose addr table has no
            // FROM entry, or a sent row with no TO entry, cannot be placed,
            // so it is left alone rather than filed under a fabricated party.
            val address = message.address?.takeIf { it.isNotBlank() } ?: return false
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
                    address = address,
                    isOutgoing = message.isOutgoing,
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
