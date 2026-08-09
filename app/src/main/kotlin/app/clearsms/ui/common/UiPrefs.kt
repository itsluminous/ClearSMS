package app.clearsms.ui.common

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** How often the local backup worker runs. */
enum class BackupFrequency {
    OFF,
    DAILY,
    WEEKLY,
}

private val Context.uiPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "ui_settings")

/**
 * UI-owned preferences that sit outside the core settings contract:
 * dynamic color, delivery reports, backup frequency and the block list mirror.
 */
@Singleton
class UiPrefs
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val dataStore = context.uiPrefsDataStore

        val dynamicColor: Flow<Boolean> = dataStore.data.map { it[KEY_DYNAMIC_COLOR] ?: true }

        suspend fun setDynamicColor(value: Boolean) {
            dataStore.edit { it[KEY_DYNAMIC_COLOR] = value }
        }

        val deliveryReports: Flow<Boolean> = dataStore.data.map { it[KEY_DELIVERY_REPORTS] ?: false }

        suspend fun setDeliveryReports(value: Boolean) {
            dataStore.edit { it[KEY_DELIVERY_REPORTS] = value }
        }

        val backupFrequency: Flow<BackupFrequency> =
            dataStore.data.map { prefs ->
                prefs[KEY_BACKUP_FREQUENCY]?.let { name ->
                    BackupFrequency.entries.firstOrNull { it.name == name }
                } ?: BackupFrequency.OFF
            }

        suspend fun setBackupFrequency(value: BackupFrequency) {
            dataStore.edit { it[KEY_BACKUP_FREQUENCY] = value.name }
        }

        /**
         * SAF tree uri of the user-chosen automatic-backup directory (a
         * persistable-permission grant taken at pick time), null until the
         * user has picked one. DAILY/WEEKLY only activate once this is set.
         */
        val backupDirectoryUri: Flow<String?> = dataStore.data.map { it[KEY_BACKUP_DIRECTORY_URI] }

        suspend fun setBackupDirectoryUri(value: String?) {
            dataStore.edit { prefs ->
                if (value == null) prefs.remove(KEY_BACKUP_DIRECTORY_URI) else prefs[KEY_BACKUP_DIRECTORY_URI] = value
            }
        }

        /**
         * Raised by the backup worker when the chosen directory is gone or
         * its permission was revoked; surfaced as a warning in Settings (the
         * fix — re-picking the directory — lives there) and cleared on the
         * next successful run or re-pick.
         */
        val backupDirectoryError: Flow<Boolean> = dataStore.data.map { it[KEY_BACKUP_DIRECTORY_ERROR] ?: false }

        suspend fun setBackupDirectoryError(value: Boolean) {
            dataStore.edit { it[KEY_BACKUP_DIRECTORY_ERROR] = value }
        }

        /** Wall-clock time of the last successful automatic backup (WEEKLY throttle). */
        val lastAutoBackupMs: Flow<Long> = dataStore.data.map { it[KEY_LAST_AUTO_BACKUP_MS] ?: 0L }

        suspend fun setLastAutoBackupMs(value: Long) {
            dataStore.edit { it[KEY_LAST_AUTO_BACKUP_MS] = value }
        }

        /** Senders the user blocked, mirrored here so the block list screen can display them. */
        val blockedSenders: Flow<Set<String>> = dataStore.data.map { it[KEY_BLOCKED_SENDERS] ?: emptySet() }

        suspend fun setSenderBlocked(
            sender: String,
            blocked: Boolean,
        ) {
            dataStore.edit { prefs ->
                val current = prefs[KEY_BLOCKED_SENDERS] ?: emptySet()
                prefs[KEY_BLOCKED_SENDERS] = if (blocked) current + sender else current - sender
            }
        }

        /**
         * Rules the user disabled, stored as "source|definitionJson" entries. A
         * disabled rule is removed from the database (so the engine skips it)
         * and parked here until re-enabled.
         */
        val disabledRules: Flow<Set<String>> = dataStore.data.map { it[KEY_DISABLED_RULES] ?: emptySet() }

        suspend fun addDisabledRule(entry: String) {
            dataStore.edit { prefs ->
                prefs[KEY_DISABLED_RULES] = (prefs[KEY_DISABLED_RULES] ?: emptySet()) + entry
            }
        }

        suspend fun removeDisabledRule(entry: String) {
            dataStore.edit { prefs ->
                prefs[KEY_DISABLED_RULES] = (prefs[KEY_DISABLED_RULES] ?: emptySet()) - entry
            }
        }

        private companion object {
            val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
            val KEY_DELIVERY_REPORTS = booleanPreferencesKey("delivery_reports")
            val KEY_BACKUP_FREQUENCY = stringPreferencesKey("backup_frequency")
            val KEY_BACKUP_DIRECTORY_URI = stringPreferencesKey("backup_directory_uri")
            val KEY_BACKUP_DIRECTORY_ERROR = booleanPreferencesKey("backup_directory_error")
            val KEY_LAST_AUTO_BACKUP_MS = longPreferencesKey("last_auto_backup_ms")
            val KEY_BLOCKED_SENDERS = stringSetPreferencesKey("blocked_senders")
            val KEY_DISABLED_RULES = stringSetPreferencesKey("disabled_rules")
        }
    }
