package app.clearsms.work

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.clearsms.BuildConfig
import app.clearsms.data.repository.RecategorizeCheckpoint
import app.clearsms.data.repository.RecategorizeCheckpointStore
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable [RecategorizeCheckpointStore] for the full re-sort, next to
 * [SyncCheckpointStore] in the same Preferences DataStore.
 *
 * **Keyed on the app version.** The checkpoint records the `versionCode`
 * that wrote it, and [get] returns null (and wipes it) for any other
 * version. [AutoResortScheduler] re-sorts the whole database on EVERY
 * version change so categorisation fixes reach existing installs; a
 * checkpoint left by the previous version must therefore never let the new
 * version "finish" a pass whose first half ran under the old rules and then
 * record itself as fully sorted. Same version, same rules, same bundled
 * asset - only then is the first half worth keeping.
 *
 * The keys are device-local progress, like the import checkpoint's: they
 * are not part of the settings backup catalogue and never travel in a
 * backup file.
 */
@Singleton
class RecategorizeCheckpointPrefs
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) : RecategorizeCheckpointStore {
        /** The running version; injectable so a test can stage an "update". */
        internal var versionCode: Int = BuildConfig.VERSION_CODE

        override suspend fun get(): RecategorizeCheckpoint? {
            val prefs = dataStore.data.first()
            val afterId = prefs[AFTER_ID_KEY] ?: return null
            if (prefs[VERSION_KEY] != versionCode) {
                // Another version's half-finished pass: worthless now, and
                // dangerous to continue (see class KDoc).
                clear()
                return null
            }
            return RecategorizeCheckpoint(
                afterId = afterId,
                processed = prefs[PROCESSED_KEY] ?: 0,
                touchedAccounts = prefs[TOUCHED_ACCOUNTS_KEY]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet(),
            )
        }

        override suspend fun set(checkpoint: RecategorizeCheckpoint) {
            dataStore.edit {
                it[AFTER_ID_KEY] = checkpoint.afterId
                it[PROCESSED_KEY] = checkpoint.processed
                it[TOUCHED_ACCOUNTS_KEY] = checkpoint.touchedAccounts.map(Long::toString).toSet()
                it[VERSION_KEY] = versionCode
            }
        }

        override suspend fun clear() {
            dataStore.edit {
                it.remove(AFTER_ID_KEY)
                it.remove(PROCESSED_KEY)
                it.remove(TOUCHED_ACCOUNTS_KEY)
                it.remove(VERSION_KEY)
            }
        }

        private companion object {
            val AFTER_ID_KEY = longPreferencesKey("recategorize_after_id")
            val PROCESSED_KEY = intPreferencesKey("recategorize_processed")
            val TOUCHED_ACCOUNTS_KEY = stringSetPreferencesKey("recategorize_touched_accounts")
            val VERSION_KEY = intPreferencesKey("recategorize_version_code")
        }
    }
