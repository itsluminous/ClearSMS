package app.clearsms.work

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import app.clearsms.data.db.MessageDao
import app.clearsms.data.db.isSameMessageAs
import app.clearsms.di.IoDispatcher
import app.clearsms.sms.ProviderSimSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-time backfill of `messages.subscriptionId` for installs that imported
 * their history before the importer read the provider's `sub_id` column -
 * without it the fix would only help fresh installs.
 *
 * Semantics, in the order they protect the user:
 * - **Fills only rows that LACK a SIM** (`subscriptionId IS NULL`); a value
 *   recorded live by [app.clearsms.receiver.SmsReceiver] is never touched.
 * - **Verifies identity before writing.** Provider `_id`s are REUSED after
 *   deletions (see [isSameMessageAs] and `releaseReusedSystemIds`), so a
 *   `systemSmsId` match alone can attach ANOTHER message's SIM to a stale
 *   row. Every write additionally requires `isSameMessageAs(body, date)`;
 *   on mismatch - or a null provider body - the row is skipped and stays
 *   null. A wrong SIM is worse than none.
 * - **Never guesses.** Rows whose provider value is missing or invalid stay
 *   null-unknown. A valid id whose subscription is no longer active on the
 *   device IS recorded (same as the live receiver path, which never
 *   validates against active SIMs): provenance is preserved, and the
 *   display path ([app.clearsms.sms.SimSelector.slotLabelFor]) already
 *   renders no tag for an inactive subscription.
 * - **Skips installs that never need it.** The pass exists ONLY for rows
 *   imported before the importer read `sub_id`. A fresh install has no such
 *   rows: the initial import records the SIM itself and marks this version
 *   done ([markDone], called by [InitialSyncWorker] after an initial run),
 *   and even without that marker the pass short-circuits - one cheap DB
 *   count, zero provider reads - when no stored row still lacks a SIM.
 *   Re-walking the whole provider next to a running import is exactly the
 *   duplicated IO that made imports slow.
 * - **Versioned one-shot**: runs once per [VERSION], marker set only after a
 *   complete pass. **Resumable**: the last processed provider id persists
 *   after every page, so an interrupted run continues instead of rescanning.
 *   **Idempotent**: re-running fills nothing new (nulls only) and is safe.
 * - **Batched off the main thread**: pages of [PAGE_SIZE] on the IO
 *   dispatcher, chunked lookups, verified rows written with ONE grouped
 *   `UPDATE ... WHERE id IN (...)` per distinct SIM per page (not one
 *   transaction per row), and at most one durable checkpoint write per
 *   page - so a large inbox never stalls the app.
 */
@Singleton
class SimBackfill
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
        private val messageDao: MessageDao,
        private val source: ProviderSimSource,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        /** Runs the pass if this [VERSION] has not completed; returns rows filled. */
        suspend fun runIfNeeded(): Int =
            withContext(ioDispatcher) {
                val prefs = dataStore.data.first()
                if ((prefs[KEY_DONE_VERSION] ?: 0) >= VERSION) return@withContext 0
                var afterId = prefs[KEY_LAST_PROVIDER_ID] ?: 0L
                // Fresh-install / nothing-to-do short-circuit: when no stored
                // row still lacks a SIM behind a provider id, a provider walk
                // cannot fill anything - mark done without reading a single
                // provider row. Only valid from a clean start; a resumed run
                // (checkpoint present) already proved work existed and must
                // finish its pass.
                if (afterId == 0L && messageDao.countNeedingSimBackfill() == 0) {
                    markDone()
                    return@withContext 0
                }
                var filled = 0
                while (true) {
                    val page = source.page(afterId, PAGE_SIZE)
                    if (page.isEmpty()) break
                    // Only rows that carry a usable SIM and an identity to verify.
                    val candidates = page.filter { it.subscriptionId != null && it.body != null }
                    if (candidates.isNotEmpty()) {
                        val byId = candidates.associateBy { it.id }
                        // Verified rows for this page, grouped by SIM so the
                        // write is one UPDATE per distinct subscription
                        // instead of one transaction per row.
                        val verified = HashMap<Int, MutableList<Long>>()
                        candidates
                            .map { it.id }
                            .chunked(CHUNK)
                            .forEach { chunk ->
                                messageDao.bySystemSmsIds(chunk).forEach { stored ->
                                    if (stored.subscriptionId != null) return@forEach
                                    val row = byId[stored.systemSmsId] ?: return@forEach
                                    // Reused-provider-id guard: the id alone is NOT
                                    // identity. Body+timestamp must agree, or the
                                    // row keeps null rather than a wrong SIM.
                                    if (stored.isSameMessageAs(row.body!!, row.dateMs)) {
                                        verified.getOrPut(row.subscriptionId!!) { mutableListOf() } += stored.id
                                    }
                                }
                            }
                        verified.forEach { (subscriptionId, ids) ->
                            ids.chunked(CHUNK).forEach { chunk ->
                                messageDao.setSubscriptionIdForIds(chunk, subscriptionId)
                            }
                            filled += ids.size
                        }
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
         * with `sub_id` reading has no pre-fix rows, so the pass would only
         * duplicate the import's provider walk to fill nothing.
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

            val KEY_DONE_VERSION = intPreferencesKey("sim_backfill_done_version")
            val KEY_LAST_PROVIDER_ID = longPreferencesKey("sim_backfill_last_provider_id")
        }
    }
