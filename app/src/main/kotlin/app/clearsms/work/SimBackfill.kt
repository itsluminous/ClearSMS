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
 * - **Versioned one-shot**: runs once per [VERSION], marker set only after a
 *   complete pass. **Resumable**: the last processed provider id persists
 *   after every page, so an interrupted run continues instead of rescanning.
 *   **Idempotent**: re-running fills nothing new (nulls only) and is safe.
 * - **Batched off the main thread**: pages of [PAGE_SIZE] on the IO
 *   dispatcher, chunked lookups, so a large inbox never stalls the app.
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
                var filled = 0
                while (true) {
                    val page = source.page(afterId, PAGE_SIZE)
                    if (page.isEmpty()) break
                    // Only rows that carry a usable SIM and an identity to verify.
                    val candidates = page.filter { it.subscriptionId != null && it.body != null }
                    if (candidates.isNotEmpty()) {
                        val byId = candidates.associateBy { it.id }
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
                                        messageDao.setSubscriptionId(stored.id, row.subscriptionId!!)
                                        filled++
                                    }
                                }
                            }
                    }
                    afterId = page.last().id
                    // Durable resume point: an interrupted run redoes at most
                    // one page (and re-filling is a no-op - nulls only).
                    dataStore.edit { it[KEY_LAST_PROVIDER_ID] = afterId }
                }
                dataStore.edit {
                    it[KEY_DONE_VERSION] = VERSION
                    it.remove(KEY_LAST_PROVIDER_ID)
                }
                filled
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
