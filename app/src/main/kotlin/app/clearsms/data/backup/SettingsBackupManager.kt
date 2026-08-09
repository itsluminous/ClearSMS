package app.clearsms.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.put
import java.io.InputStream
import java.io.OutputStream

/** Outcome of a settings restore, surfaced to the user. */
data class SettingsRestoreResult(
    /** Entries recognised and written to the DataStore. */
    val applied: Int,
    /** Entries skipped: unknown keys, excluded keys, or wrong-typed values. */
    val skipped: Int,
)

/**
 * One preference the settings backup covers: its stored DataStore key name
 * plus how its value maps to and from JSON. Only the TYPE is validated on
 * import - value-level sanity (unknown enum names, stale pill orders) is
 * already handled leniently by [app.clearsms.data.prefs.SettingsRepositoryImpl]'s
 * readers, so duplicating that validation here would only drift out of sync.
 */
internal sealed class SettingsBackupEntry(
    val name: String,
) {
    /** The stored value as JSON, or null when the preference was never set. */
    abstract fun export(prefs: Preferences): JsonElement?

    /**
     * Validates [element]'s type and returns a write to apply, or null when
     * the value is wrong-typed and the entry must be counted as skipped.
     */
    abstract fun prepare(element: JsonElement): ((MutablePreferences) -> Unit)?

    class BooleanEntry(
        name: String,
    ) : SettingsBackupEntry(name) {
        private val key = booleanPreferencesKey(name)

        override fun export(prefs: Preferences): JsonElement? = prefs[key]?.let(::JsonPrimitive)

        override fun prepare(element: JsonElement): ((MutablePreferences) -> Unit)? {
            val value = (element as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return null
            return { it[key] = value }
        }
    }

    class StringEntry(
        name: String,
    ) : SettingsBackupEntry(name) {
        private val key = stringPreferencesKey(name)

        override fun export(prefs: Preferences): JsonElement? = prefs[key]?.let(::JsonPrimitive)

        override fun prepare(element: JsonElement): ((MutablePreferences) -> Unit)? {
            val value = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            return { it[key] = value }
        }
    }

    class StringSetEntry(
        name: String,
    ) : SettingsBackupEntry(name) {
        private val key = stringSetPreferencesKey(name)

        override fun export(prefs: Preferences): JsonElement? = prefs[key]?.let { set -> JsonArray(set.sorted().map(::JsonPrimitive)) }

        override fun prepare(element: JsonElement): ((MutablePreferences) -> Unit)? {
            val array = element as? JsonArray ?: return null
            val values =
                array.map { item ->
                    (item as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                }
            return { it[key] = values.toSet() }
        }
    }
}

/**
 * The complete inventory of settings the backup covers, plus the explicit
 * exclusion list. Every key written by SettingsRepositoryImpl MUST appear in
 * exactly one of the two - a test enforces this so a future preference can
 * never be silently forgotten.
 */
internal object SettingsBackupCatalog {
    val entries: List<SettingsBackupEntry> =
        listOf(
            SettingsBackupEntry.StringEntry("theme"),
            SettingsBackupEntry.BooleanEntry("otp_auto_copy"),
            SettingsBackupEntry.StringEntry("otp_auto_delete_policy"),
            SettingsBackupEntry.StringEntry("otp_display_size"),
            SettingsBackupEntry.BooleanEntry("show_transaction_details"),
            SettingsBackupEntry.BooleanEntry("recycle_bin_enabled"),
            SettingsBackupEntry.StringEntry("signature"),
            SettingsBackupEntry.BooleanEntry("show_rich_avatars"),
            SettingsBackupEntry.StringSetEntry("notification_actions"),
            SettingsBackupEntry.StringEntry("swipe_action_start"),
            SettingsBackupEntry.StringEntry("swipe_action_end"),
            SettingsBackupEntry.StringEntry("default_destination"),
            SettingsBackupEntry.StringEntry("default_inbox_filter"),
            SettingsBackupEntry.StringEntry("default_finance_filter"),
            SettingsBackupEntry.BooleanEntry("transaction_notifications"),
            SettingsBackupEntry.StringEntry("logo_background"),
            SettingsBackupEntry.StringEntry("inbox_pill_order"),
            SettingsBackupEntry.StringEntry("finance_pill_order"),
            SettingsBackupEntry.StringEntry("alerts_pill_order"),
        )

    val byName: Map<String, SettingsBackupEntry> = entries.associateBy { it.name }

    /**
     * Keys deliberately NEVER backed up or restored:
     * - `show_balance` - security-sensitive: it gates financial balances
     *   behind the device screen lock, so a restored file must not be able
     *   to silently disable that protection (and enabling it goes through
     *   [app.clearsms.ui.finance.BalanceVisibility.conceal], which a raw
     *   DataStore write would bypass);
     * - `onboarding_complete` - device lifecycle state: restoring `true`
     *   onto a fresh install would skip the permission/default-app
     *   onboarding the new device still needs;
     * - `handled_otp_message_id` - device-bound: message ids are local to
     *   this install's database, so the value is meaningless elsewhere and
     *   restoring it could hide a live OTP banner.
     */
    val excludedKeys: Set<String> =
        setOf(
            "show_balance",
            "onboarding_complete",
            "handled_otp_message_id",
        )
}

/**
 * Local backup and restore of the app's settings (the Preferences DataStore)
 * as a single JSON document - the settings sibling of [BackupManager], which
 * covers the database. Backups are plain files the user controls; nothing
 * ever leaves the device.
 *
 * Unlike the database restore, a document with a NEWER format version is not
 * rejected: settings are independent key/value pairs, so the recognised
 * entries are applied and the rest reported as skipped - the honest best
 * effort for a file from a future app version.
 */
class SettingsBackupManager(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
    private val appVersion: String,
) {
    /**
     * Serializes every set, non-excluded preference to [output] as JSON.
     * The stream is not closed. Preferences still at their defaults (never
     * written) are omitted: restore then only touches what the user changed.
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun exportTo(output: OutputStream) {
        val prefs = dataStore.data.first()
        val settings =
            buildJsonObject {
                SettingsBackupCatalog.entries.forEach { entry ->
                    entry.export(prefs)?.let { put(entry.name, it) }
                }
            }
        val document =
            buildJsonObject {
                put("type", DOCUMENT_TYPE)
                put("formatVersion", FORMAT_VERSION)
                put("appVersion", appVersion)
                put("createdAt", System.currentTimeMillis())
                put("settings", settings)
            }
        json.encodeToStream(JsonObject.serializer(), document, output)
    }

    /**
     * Applies the settings backup read from [input].
     *
     * Safety properties:
     * - the ENTIRE document is decoded and every entry validated BEFORE any
     *   mutation, so an unparseable or non-settings file changes nothing;
     * - unknown keys, excluded keys and wrong-typed values never throw -
     *   they are skipped and tallied in the returned [SettingsRestoreResult];
     * - all recognised entries land in one [DataStore.edit], so the apply is
     *   atomic and every settings Flow picks the change up immediately.
     *
     * @throws IllegalArgumentException when the stream is not a settings
     * backup (corrupt JSON, missing marker, or a database backup file).
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun importFrom(input: InputStream): SettingsRestoreResult {
        val document =
            try {
                json.decodeFromStream(JsonObject.serializer(), input)
            } catch (e: Exception) {
                throw IllegalArgumentException("Not a valid settings backup file", e)
            }
        val type = (document["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val settings = document["settings"] as? JsonObject
        if (type != DOCUMENT_TYPE || settings == null) {
            throw IllegalArgumentException("Not a settings backup file")
        }

        var skipped = 0
        val writes = mutableListOf<(MutablePreferences) -> Unit>()
        settings.forEach { (name, element) ->
            val entry = SettingsBackupCatalog.byName[name]
            val write = entry?.prepare(element)
            if (write == null) skipped++ else writes += write
        }
        if (writes.isNotEmpty()) {
            dataStore.edit { prefs -> writes.forEach { it(prefs) } }
        }
        return SettingsRestoreResult(applied = writes.size, skipped = skipped)
    }

    companion object {
        /** Marker distinguishing settings backups from database backups. */
        const val DOCUMENT_TYPE = "clearsms-settings"

        /** Current settings backup document format. */
        const val FORMAT_VERSION = 1
    }
}
