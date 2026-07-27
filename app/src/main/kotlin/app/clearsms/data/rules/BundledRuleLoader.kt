package app.clearsms.data.rules

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.clearsms.data.db.RuleDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * Loads the bundled rules asset (`default_rules.json`) into the rules table.
 *
 * Runs on first launch and whenever the bundled document version changes
 * (i.e. after an app update shipping newer community rules). Builtin rows are
 * replaced wholesale; user rules are never touched.
 */
class BundledRuleLoader(
    private val context: Context,
    private val ruleDao: RuleDao,
    private val json: Json,
    private val dataStore: DataStore<Preferences>,
) {
    /** Version string of the currently loaded bundled document, if any. */
    suspend fun loadedVersion(): String? = dataStore.data.first()[LOADED_VERSION_KEY]

    /** Observable form of [loadedVersion]. */
    val loadedVersionFlow: Flow<String?> =
        dataStore.data.map { it[LOADED_VERSION_KEY] }

    /** Ensures builtin rules in Room match the bundled asset; returns the version. */
    suspend fun ensureLoaded(): String? {
        val document =
            try {
                val text =
                    context.assets
                        .open(ASSET_NAME)
                        .bufferedReader()
                        .use { it.readText() }
                json.decodeFromString(RuleDocument.serializer(), text)
            } catch (e: Exception) {
                Log.w(TAG, "Could not load bundled rules", e)
                return loadedVersion()
            }
        if (loadedVersion() == document.version) return document.version

        ruleDao.deleteBySource(RuleSources.BUILTIN)
        ruleDao.insertAll(document.rules.map { it.toEntity(json, RuleSources.BUILTIN) })
        dataStore.edit { it[LOADED_VERSION_KEY] = document.version }
        return document.version
    }

    private companion object {
        const val TAG = "BundledRuleLoader"
        const val ASSET_NAME = "default_rules.json"
        val LOADED_VERSION_KEY = stringPreferencesKey("bundled_rules_version")
    }
}
