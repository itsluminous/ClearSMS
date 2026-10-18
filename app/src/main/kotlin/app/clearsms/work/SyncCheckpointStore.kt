package app.clearsms.work

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable progress checkpoint for the initial message-history import.
 *
 * The import walks the system SMS provider ordered by `_id`; after each page
 * commits, the checkpoint advances to the last processed `_id`. A stopped,
 * retried or rebooted import resumes from here instead of starting over -
 * a mid-page kill redoes at most one page (which the unique `systemSmsId`
 * index then deduplicates).
 */
@Singleton
class SyncCheckpointStore
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) {
        /**
         * @property lastSystemSmsId highest system provider `_id` whose page
         *   has been fully committed; 0 when nothing was processed yet.
         * @property processedCount messages processed so far (drives progress).
         */
        data class Checkpoint(
            val lastSystemSmsId: Long = 0L,
            val processedCount: Int = 0,
        )

        suspend fun get(): Checkpoint {
            val prefs = dataStore.data.first()
            return Checkpoint(
                lastSystemSmsId = prefs[LAST_ID_KEY] ?: 0L,
                processedCount = prefs[PROCESSED_KEY] ?: 0,
            )
        }

        suspend fun set(checkpoint: Checkpoint) {
            dataStore.edit {
                it[LAST_ID_KEY] = checkpoint.lastSystemSmsId
                it[PROCESSED_KEY] = checkpoint.processedCount
            }
        }

        suspend fun clear() {
            dataStore.edit {
                it.remove(LAST_ID_KEY)
                it.remove(PROCESSED_KEY)
                it.remove(LAST_MMS_ID_KEY)
                it.remove(MMS_SCOPE_KEY)
            }
        }

        /**
         * Highest system MMS provider `_id` whose import page has committed;
         * 0 when the MMS history has not been walked yet.
         *
         * Separate from [Checkpoint.lastSystemSmsId] because the two
         * providers number their rows independently - one shared cursor
         * would skip or re-read whole stretches of the other's history.
         */
        suspend fun lastSystemMmsId(): Long = dataStore.data.first()[LAST_MMS_ID_KEY] ?: 0L

        suspend fun setLastSystemMmsId(id: Long) {
            dataStore.edit { it[LAST_MMS_ID_KEY] = id }
        }

        /**
         * Which provider boxes the MMS walk behind [lastSystemMmsId] has
         * covered - one of the `MMS_SCOPE_*` constants; 0 when never recorded.
         *
         * Why a checkpoint needs a scope: the walk is by `_id`, and the
         * import that shipped in 0.23.0 read the INBOX only. A sent MMS sits
         * at an id the checkpoint has long since passed, so simply widening
         * the query would never reach it on an existing install (issue
         * #112). A scope below [MMS_SCOPE_CURRENT] tells the importer to
         * walk again from the start, and tells the catch-up gap probe that
         * such a walk is owed.
         */
        suspend fun mmsImportScope(): Int = dataStore.data.first()[MMS_SCOPE_KEY] ?: MMS_SCOPE_NONE

        /**
         * Starts a fresh MMS walk under the current scope: the checkpoint
         * returns to 0 and the scope is recorded in the SAME write, so a
         * walk interrupted half-way resumes from its checkpoint instead of
         * starting over on every attempt - exactly as the first import did.
         */
        suspend fun beginMmsWalk() {
            dataStore.edit {
                it[LAST_MMS_ID_KEY] = 0L
                it[MMS_SCOPE_KEY] = MMS_SCOPE_CURRENT
            }
        }

        companion object {
            private val LAST_ID_KEY = longPreferencesKey("initial_sync_last_system_sms_id")
            private val PROCESSED_KEY = intPreferencesKey("initial_sync_processed_count")
            private val LAST_MMS_ID_KEY = longPreferencesKey("initial_sync_last_system_mms_id")
            private val MMS_SCOPE_KEY = intPreferencesKey("initial_sync_mms_scope")

            /** No scope recorded: the 0.23.0 inbox-only walk, or no walk yet. */
            const val MMS_SCOPE_NONE = 0

            /** Inbox + sent (issue #112). */
            const val MMS_SCOPE_INBOX_AND_SENT = 2

            /** The scope every complete walk must have covered. */
            const val MMS_SCOPE_CURRENT = MMS_SCOPE_INBOX_AND_SENT
        }
    }
