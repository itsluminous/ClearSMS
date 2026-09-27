package app.clearsms.data.rules

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.clearsms.data.db.RuleDao
import app.clearsms.diagnostics.Diag
import app.clearsms.diagnostics.DiagField.Companion.count
import app.clearsms.diagnostics.DiagField.Companion.flag
import app.clearsms.diagnostics.DiagField.Companion.ruleId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Loads the bundled rules asset (`default_rules.json`) into the rules table.
 *
 * Runs on first launch and whenever the bundled document changes (i.e. after
 * an app update shipping newer community rules). Builtin rows are replaced
 * wholesale; user rules are never touched.
 *
 * "Changes" is judged on the document's `version` AND a fingerprint of the
 * asset text: 0.20.0 added a rule without bumping the version, so the reseed
 * was skipped for every updating user and the new rule never reached their
 * table until they wiped app data (issue #43's missed transactions). The
 * fingerprint makes forgetting the bump harmless.
 */
class BundledRuleLoader(
    private val context: Context,
    private val ruleDao: RuleDao,
    private val json: Json,
    private val dataStore: DataStore<Preferences>,
    /**
     * The UI preferences store, read only for the legacy `disabled_rules`
     * set: versions up to 0.20.0 disabled a rule by deleting its row and
     * parking `source|definitionJson` there. A builtin parked that way must
     * come back from the reseed DISABLED, or the update that first runs this
     * code silently switches every rule the user had turned off back on.
     * Null (tests) means no legacy store.
     */
    private val uiDataStore: DataStore<Preferences>? = null,
) {
    /** Version string of the currently loaded bundled document, if any. */
    suspend fun loadedVersion(): String? = dataStore.data.first()[LOADED_VERSION_KEY]

    /** Observable form of [loadedVersion]. */
    val loadedVersionFlow: Flow<String?> =
        dataStore.data.map { it[LOADED_VERSION_KEY] }

    /** Ensures builtin rules in Room match the bundled asset; returns the version. */
    suspend fun ensureLoaded(): String? {
        val text =
            try {
                context.assets
                    .open(ASSET_NAME)
                    .bufferedReader()
                    .use { it.readText() }
            } catch (e: Exception) {
                Diag.w(TAG, "could not load bundled rules", e)
                return loadedVersion()
            }
        return ensureLoaded(text)
    }

    /** [ensureLoaded] over an already-read document text (asset access factored out for tests). */
    suspend fun ensureLoaded(text: String): String? {
        val document =
            try {
                json.decodeFromString(RuleDocument.serializer(), text)
            } catch (e: Exception) {
                Diag.w(TAG, "could not parse bundled rules", e)
                return loadedVersion()
            }
        val fingerprint = fingerprint(text)
        val prefs = dataStore.data.first()
        if (prefs[LOADED_VERSION_KEY] == document.version && prefs[LOADED_FINGERPRINT_KEY] == fingerprint) {
            return document.version
        }

        // The reseed itself; the loaded document version is part of the
        // diagnostic report's header rather than repeated here.
        Diag.i(TAG, "bundled rules reseeded", count("rules", document.rules.size), flag("firstLoad", prefs[LOADED_VERSION_KEY] == null))
        reseed(document)
        dataStore.edit {
            it[LOADED_VERSION_KEY] = document.version
            it[LOADED_FINGERPRINT_KEY] = fingerprint
        }
        return document.version
    }

    /**
     * Replaces the builtin rows with [document]'s rules.
     *
     * Two things survive the replacement:
     * - **User rules, always.** The insert uses REPLACE-on-id, so a bundled
     *   rule whose id a USER row already holds would silently overwrite -
     *   destroy - that user rule. Such a bundled rule is skipped instead: the
     *   user's copy is the one they can see and edit, and a reseed must never
     *   be the thing that loses a rule the user still wants.
     * - **The disabled state of builtin rules.** `enabled` lives on the row,
     *   so deleting and re-inserting the builtin set would silently switch
     *   every disabled builtin back on; the flag is carried across. A builtin
     *   a pre-0.21 version parked in preferences (its row deleted) counts as
     *   disabled too - see [legacyParkedBuiltinIds].
     */
    suspend fun reseed(document: RuleDocument) {
        val userIds = ruleDao.getBySource(RuleSources.USER).map { it.id }.toSet()
        val disabledBuiltins =
            ruleDao
                .getBySource(RuleSources.BUILTIN)
                .filterNot { it.enabled }
                .map { it.id }
                .toSet() + legacyParkedBuiltinIds()
        ruleDao.deleteBySource(RuleSources.BUILTIN)
        val rows =
            document.rules
                .filter { rule ->
                    val shadowed = rule.id in userIds
                    if (shadowed) Diag.w(TAG, "bundled rule skipped: a user rule owns that id", null, ruleId(rule.id))
                    !shadowed
                }.map { rule ->
                    rule.toEntity(json, RuleSources.BUILTIN).copy(enabled = rule.id !in disabledBuiltins)
                }
        ruleDao.insertAll(rows)
    }

    /** Ids of builtin rules a pre-0.21 version disabled by parking them in the UI preferences. */
    private suspend fun legacyParkedBuiltinIds(): Set<String> {
        val parked = uiDataStore?.data?.first()?.get(LEGACY_DISABLED_RULES_KEY) ?: return emptySet()
        return parked
            .filter { it.startsWith(RuleSources.BUILTIN + "|") }
            .mapNotNull { entry ->
                try {
                    json.decodeFromString(RuleDefinition.serializer(), entry.substringAfter('|')).id
                } catch (_: Exception) {
                    null
                }
            }.toSet()
    }

    companion object {
        private const val TAG = "BundledRuleLoader"
        private const val ASSET_NAME = "default_rules.json"
        private val LOADED_VERSION_KEY = stringPreferencesKey("bundled_rules_version")
        private val LOADED_FINGERPRINT_KEY = stringPreferencesKey("bundled_rules_fingerprint")

        /** Mirrors `UiPrefs.KEY_DISABLED_RULES`; the legacy parked-rule set in the UI store. */
        private val LEGACY_DISABLED_RULES_KEY = stringSetPreferencesKey("disabled_rules")

        /** SHA-256 of the document text, hex; what "the asset changed" is judged on. */
        fun fingerprint(text: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(text.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
