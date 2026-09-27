package app.clearsms.work

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import app.clearsms.data.db.DateSentUpdate
import app.clearsms.data.db.MessageDao
import app.clearsms.data.db.isSameMessageAs
import app.clearsms.di.IoDispatcher
import app.clearsms.sms.ProviderSentTimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-time backfill of `messages.dateSent` (the sender's network timestamp,
 * GitHub #45) for installs that imported their history before the importer
 * read the provider's `date_sent` column - without it the sent-time sort
 * and the details row would only ever help fresh installs.
 *
 * A deliberate copy of the [SimBackfill] contract, because a careless
 * provider walk once made the initial import visibly slower:
 * - **Fills only INCOMING rows that LACK a sent time** (`dateSent IS
 *   NULL`); a value recorded live by [app.clearsms.receiver.SmsReceiver]
 *   or by the importer is never touched.
 * - **Verifies identity before writing.** Provider `_id`s are REUSED after
 *   deletions (see [isSameMessageAs]), so a `systemSmsId` match alone can
 *   attach ANOTHER message's sent time to a stale row. Every write
 *   additionally requires `isSameMessageAs(body, date)`; on mismatch - or
 *   a null provider body - the row is skipped and stays null. A wrong sent
 *   time is worse than none.
 * - **Never guesses.** Rows whose provider `date_sent` is 0/absent stay
 *   null-unknown (the sort then falls back to the received time, and the
 *   details view says the network reported none).
 * - **Skips installs that never need it.** A fresh install has no pre-fix
 *   rows: the initial import records `date_sent` itself and marks this
 *   version done ([markDone], called by [InitialSyncWorker] after an
 *   initial run), and even without that marker the pass short-circuits -
 *   one cheap DB count, zero provider reads - when no stored incoming row
 *   still lacks a sent time. It is never run next to a live import (see
 *   [SimBackfillWorker], which defers both passes while an import is
 *   enqueued or running).
 * - **Versioned one-shot**: runs once per [VERSION], marker set only after
 *   a complete pass. **Resumable**: the last processed provider id persists
 *   after every page. **Idempotent**: re-running fills nothing new.
 * - **Batched off the main thread**: pages of [PAGE_SIZE] on the IO
 *   dispatcher, chunked lookups, and the page's verified rows written with
 *   ONE grouped transaction per chunk ([MessageDao.setDateSentBatch]) -
 *   never one transaction per row - plus at most one durable checkpoint
 *   write per page.
 */
@Singleton
class SentTimeBackfill
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
        private val messageDao: MessageDao,
        private val source: ProviderSentTimeSource,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        /** Runs the pass if this [VERSION] has not completed; returns rows filled. */
        suspend fun runIfNeeded(): Int =
            withContext(ioDispatcher) {
                val prefs = dataStore.data.first()
                if ((prefs[KEY_DONE_VERSION] ?: 0) >= VERSION) return@withContext 0
                var afterId = prefs[KEY_LAST_PROVIDER_ID] ?: 0L
                // Fresh-install / nothing-to-do short-circuit, decided from
                // the DB alone - zero provider reads. Only valid from a clean
                // start; a resumed run (checkpoint present) already proved
                // work existed and must finish its pass.
                if (afterId == 0L && messageDao.countNeedingSentTimeBackfill() == 0) {
                    markDone()
                    return@withContext 0
                }
                var filled = 0
                while (true) {
                    val page = source.page(afterId, PAGE_SIZE)
                    if (page.isEmpty()) break
                    // Only rows that carry a sent time and an identity to verify.
                    val candidates = page.filter { it.dateSentMs != null && it.body != null }
                    if (candidates.isNotEmpty()) {
                        val byId = candidates.associateBy { it.id }
                        val verified = ArrayList<DateSentUpdate>(candidates.size)
                        candidates
                            .map { it.id }
                            .chunked(CHUNK)
                            .forEach { chunk ->
                                messageDao.bySystemSmsIds(chunk).forEach { stored ->
                                    if (stored.isOutgoing || stored.dateSent != null) return@forEach
                                    val row = byId[stored.systemSmsId] ?: return@forEach
                                    // Reused-provider-id guard: the id alone is NOT
                                    // identity. Body+received time must agree, or
                                    // the row keeps null rather than a wrong time.
                                    if (stored.isSameMessageAs(row.body!!, row.dateMs)) {
                                        verified += DateSentUpdate(stored.id, row.dateSentMs!!)
                                    }
                                }
                            }
                        // One grouped write per chunk of the page - not one per row.
                        verified.chunked(CHUNK).forEach { chunk -> messageDao.setDateSentBatch(chunk) }
                        filled += verified.size
                    }
                    afterId = page.last().id
                    // Durable resume point, at most one write per page: an
                    // interrupted run redoes at most one page (and re-filling
                    // is a no-op - nulls only).
                    dataStore.edit { it[KEY_LAST_PROVIDER_ID] = afterId }
                }
                markDone()
                filled
            }

        /**
         * Records this [VERSION] as complete. Called after a full pass, and
         * by [InitialSyncWorker] after an INITIAL import: a history imported
         * with `date_sent` reading has no pre-fix rows, so the pass would
         * only duplicate the import's provider walk to fill nothing.
         */
        suspend fun markDone() {
            dataStore.edit {
                it[KEY_DONE_VERSION] = VERSION
                it.remove(KEY_LAST_PROVIDER_ID)
            }
        }

        companion object {
            /** Bump to re-run the pass after a future correctness fix. */
            const val VERSION = 1
            private const val PAGE_SIZE = 500

            /** Below SQLite's 999-variable bind limit for the IN () lookup. */
            private const val CHUNK = 900

            val KEY_DONE_VERSION = intPreferencesKey("sent_time_backfill_done_version")
            val KEY_LAST_PROVIDER_ID = longPreferencesKey("sent_time_backfill_last_provider_id")
        }
    }
