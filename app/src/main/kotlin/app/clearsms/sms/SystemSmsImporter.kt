package app.clearsms.sms

import android.content.Context
import android.provider.Telephony
import android.util.Log
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.repository.ImportedSmsRow
import app.clearsms.data.repository.MessageRepositoryImpl
import app.clearsms.di.IoDispatcher
import app.clearsms.work.SyncCheckpointStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bulk-imports the existing message history from the system SMS provider
 * (`content://sms`) into the local database.
 *
 * Designed to survive interruption and re-runs:
 * - **Resumable** - rows are read in `_id` order in pages of [PAGE_SIZE];
 *   after each page's transaction commits, a durable [SyncCheckpointStore]
 *   checkpoint advances. A killed or retried import resumes from the
 *   checkpoint and redoes at most one page.
 * - **Idempotent** - every imported row carries its system `_id`, guarded by
 *   a unique index; re-processing a row can never duplicate it.
 * - **Parallel** - pages are read by a single reader on the IO dispatcher,
 *   classification (CPU-bound regex work) fans out across a bounded
 *   [Dispatchers.Default] slice, and results are batch-inserted in one
 *   transaction per page. The decoded rule set is snapshotted once for the
 *   whole run instead of being re-decoded per message.
 *
 * Incoming messages go through the full categorization + extraction pipeline;
 * outgoing (sent) messages are stored directly as read personal messages.
 */
@Singleton
class SystemSmsImporter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: MessageRepositoryImpl,
        private val checkpointStore: SyncCheckpointStore,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        /** One raw row read from the system SMS provider. */
        private data class RawSms(
            val id: Long,
            val address: String?,
            val body: String?,
            val date: Long,
            val read: Boolean,
            val incoming: Boolean,
            /** Provider `status` == STATUS_COMPLETE (delivery report received). */
            val delivered: Boolean,
        )

        /**
         * Result of one import run.
         *
         * @property inserted messages newly inserted by this run.
         * @property freshMessages up to [CatchUpNotifier.MAX_INDIVIDUAL]
         *   inserted INCOMING messages newer than the pre-run watermark -
         *   messages the user has never been notified about. Empty on a
         *   fresh install (null watermark: all history is "old").
         * @property freshCount total count of such messages (may exceed
         *   [freshMessages]'s capped size).
         */
        data class ImportResult(
            val inserted: Int,
            val freshMessages: List<MessageEntity>,
            val freshCount: Int,
            /**
             * True when this run was the INITIAL history import (empty
             * database - null watermark), as opposed to a catch-up run over
             * an existing database. The initial import classifies EVERYTHING
             * with the current rules, so its completion may record the
             * version as fully sorted; a catch-up only adds new rows and
             * must never claim that.
             */
            val initialRun: Boolean = false,
        )

        /**
         * Imports inbox and sent messages in `_id` order, resuming from the
         * durable checkpoint. Invokes [onProgress] with (processed, total)
         * once per committed page.
         */
        suspend fun importAll(onProgress: suspend (imported: Int, total: Int) -> Unit = { _, _ -> }): ImportResult =
            withContext(ioDispatcher) {
                val checkpoint = checkpointStore.get()
                val remaining = countRemaining(checkpoint.lastSystemSmsId)
                val total = checkpoint.processedCount + remaining
                var processed = checkpoint.processedCount
                onProgress(processed, total)
                if (remaining == 0) {
                    return@withContext ImportResult(0, emptyList(), 0, initialRun = repository.newestTimestamp() == null)
                }

                // Notification watermark, read BEFORE the first page commits:
                // anything already stored has been seen (and, when eligible,
                // notified) - so only imported rows NEWER than this are
                // messages the user missed. Null = empty database = fresh
                // install: the whole initial import is old history, silent.
                val watermark = repository.newestTimestamp()

                val snapshot = repository.rulesSnapshot()
                val parallelism = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
                val classifyDispatcher = Dispatchers.Default.limitedParallelism(parallelism)

                var cursorId = checkpoint.lastSystemSmsId
                var inserted = 0
                val freshMessages = ArrayList<MessageEntity>(FRESH_SAMPLE_LIMIT)
                var freshCount = 0
                while (true) {
                    val page = readPage(cursorId, PAGE_SIZE)
                    if (page.isEmpty()) break
                    val rows =
                        coroutineScope {
                            page
                                .mapNotNull { raw ->
                                    val address = raw.address ?: return@mapNotNull null
                                    val body = raw.body ?: return@mapNotNull null
                                    async(classifyDispatcher) {
                                        ImportedSmsRow(
                                            systemSmsId = raw.id,
                                            sender = address,
                                            body = body,
                                            timestampMs = raw.date,
                                            isRead = raw.read,
                                            enriched =
                                                if (raw.incoming) {
                                                    repository.classify(snapshot, address, body, raw.date)
                                                } else {
                                                    null
                                                },
                                            delivered = raw.delivered,
                                        )
                                    }
                                }.awaitAll()
                        }
                    val insertedEntities = repository.persistImportedPage(rows)
                    inserted += insertedEntities.size
                    if (watermark != null) {
                        for (entity in insertedEntities) {
                            // deletedAt != null = keyword-binned at import:
                            // never "fresh", never notified.
                            if (entity.isOutgoing ||
                                entity.isBlockedSender ||
                                entity.deletedAt != null ||
                                entity.timestamp <= watermark
                            ) {
                                continue
                            }
                            freshCount++
                            if (freshMessages.size < FRESH_SAMPLE_LIMIT) freshMessages += entity
                        }
                    }
                    processed += page.size
                    cursorId = page.last().id
                    // Advance the checkpoint only after the page's transaction
                    // committed, so an interruption redoes at most one page.
                    checkpointStore.set(SyncCheckpointStore.Checkpoint(cursorId, processed))
                    onProgress(processed, total)
                }
                ImportResult(inserted, freshMessages, freshCount, initialRun = watermark == null)
            }

        /** Counts importable rows past the checkpoint (for progress totals). */
        private fun countRemaining(afterId: Long): Int =
            query(
                projection = arrayOf(Telephony.Sms._ID),
                afterId = afterId,
                sortOrder = null,
            )?.use { it.count } ?: 0

        /** Reads the next page of importable rows in ascending `_id` order. */
        private fun readPage(
            afterId: Long,
            limit: Int,
        ): List<RawSms> {
            val cursor =
                query(
                    projection = PROJECTION,
                    afterId = afterId,
                    sortOrder = "${Telephony.Sms._ID} ASC LIMIT $limit",
                ) ?: return emptyList()
            return cursor.use {
                val idIdx = it.getColumnIndex(Telephony.Sms._ID)
                val addressIdx = it.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIdx = it.getColumnIndex(Telephony.Sms.BODY)
                val dateIdx = it.getColumnIndex(Telephony.Sms.DATE)
                val typeIdx = it.getColumnIndex(Telephony.Sms.TYPE)
                val readIdx = it.getColumnIndex(Telephony.Sms.READ)
                val statusIdx = it.getColumnIndex(Telephony.Sms.STATUS)
                buildList {
                    while (it.moveToNext()) {
                        add(
                            RawSms(
                                id = it.getLong(idIdx),
                                address = it.getString(addressIdx),
                                body = it.getString(bodyIdx),
                                date = it.getLong(dateIdx),
                                read = it.getInt(readIdx) == 1,
                                incoming = it.getInt(typeIdx) == Telephony.Sms.MESSAGE_TYPE_INBOX,
                                // Guarded: some providers ignore the projection
                                // and omit STATUS entirely (index -1).
                                delivered =
                                    statusIdx >= 0 &&
                                        !it.isNull(statusIdx) &&
                                        it.getInt(statusIdx) == Telephony.Sms.STATUS_COMPLETE,
                            ),
                        )
                    }
                }
            }
        }

        private fun query(
            projection: Array<String>,
            afterId: Long,
            sortOrder: String?,
        ) = try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                SELECTION,
                arrayOf(afterId.toString()),
                sortOrder,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Cannot query the system SMS provider", e)
            null
        }

        private companion object {
            const val TAG = "SystemSmsImporter"
            const val PAGE_SIZE = 500

            /**
             * At most this many fresh entities are materialized for
             * per-message notifications; past it only the count matters
             * (the notifier collapses to one summary). Mirrors
             * [app.clearsms.notification.CatchUpNotifier.MAX_INDIVIDUAL].
             */
            const val FRESH_SAMPLE_LIMIT = 5

            /** Inbox and sent rows past the checkpoint; drafts/outbox/failed are skipped. */
            val SELECTION =
                "${Telephony.Sms._ID} > ? AND ${Telephony.Sms.TYPE} IN " +
                    "(${Telephony.Sms.MESSAGE_TYPE_INBOX}, ${Telephony.Sms.MESSAGE_TYPE_SENT})"

            val PROJECTION =
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.TYPE,
                    Telephony.Sms.READ,
                    Telephony.Sms.STATUS,
                )
        }
    }
