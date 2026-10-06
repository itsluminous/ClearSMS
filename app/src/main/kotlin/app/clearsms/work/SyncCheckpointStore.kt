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

        private companion object {
            val LAST_ID_KEY = longPreferencesKey("initial_sync_last_system_sms_id")
            val PROCESSED_KEY = intPreferencesKey("initial_sync_processed_count")
            val LAST_MMS_ID_KEY = longPreferencesKey("initial_sync_last_system_mms_id")
        }
    }
