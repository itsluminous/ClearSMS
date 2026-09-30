package app.clearsms.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.clearsms.data.db.RuleEntity
import app.clearsms.data.rules.RuleDocument
import app.clearsms.data.rules.RuleImporter
import app.clearsms.data.rules.RuleSources
import app.clearsms.data.rules.toDefinition
import kotlinx.coroutines.flow.first
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
    /** User rules added or updated from the file's rules section (0 for a rules-less file). */
    val rules: Int = 0,
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
            SettingsBackupEntry.StringEntry("message_sort_order"),
            SettingsBackupEntry.BooleanEntry("recycle_bin_enabled"),
            SettingsBackupEntry.BooleanEntry("delayed_send_enabled"),
            SettingsBackupEntry.StringEntry("delayed_send_delay"),
            SettingsBackupEntry.StringEntry("signature"),
            SettingsBackupEntry.BooleanEntry("show_rich_avatars"),
            SettingsBackupEntry.StringSetEntry("notification_actions"),
            SettingsBackupEntry.StringEntry("swipe_action_start"),
            SettingsBackupEntry.StringEntry("swipe_action_end"),
            SettingsBackupEntry.StringEntry("swipe_dead_zone"),
            SettingsBackupEntry.StringEntry("default_destination"),
            SettingsBackupEntry.BooleanEntry("inbox_section_enabled"),
            SettingsBackupEntry.BooleanEntry("finance_section_enabled"),
            SettingsBackupEntry.BooleanEntry("alerts_section_enabled"),
            SettingsBackupEntry.StringEntry("default_inbox_filter"),
            SettingsBackupEntry.StringEntry("default_finance_filter"),
            SettingsBackupEntry.StringEntry("finance_currency"),
            SettingsBackupEntry.BooleanEntry("transaction_notifications"),
            SettingsBackupEntry.StringEntry("logo_background"),
            SettingsBackupEntry.StringEntry("inbox_pill_order"),
            SettingsBackupEntry.StringSetEntry("inbox_hidden_pills"),
            SettingsBackupEntry.BooleanEntry("inbox_unread_toggle"),
            SettingsBackupEntry.StringEntry("finance_pill_order"),
            SettingsBackupEntry.StringSetEntry("finance_hidden_pills"),
            SettingsBackupEntry.StringEntry("alerts_pill_order"),
            SettingsBackupEntry.StringSetEntry("alerts_hidden_pills"),
            SettingsBackupEntry.StringSetEntry("blocked_keywords"),
            SettingsBackupEntry.StringSetEntry("blocked_senders"),
            SettingsBackupEntry.StringSetEntry("muted_senders"),
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
     *   restoring it could hide a live OTP banner;
     * - `schedule_send_tip_shown` - per-install education state (the
     *   one-time "long-press Send to schedule" tip), like
     *   `onboarding_complete`: each install teaches its own user once;
     * - `last_sorted_version_code` - device/version state: which app
     *   versionCode last fully sorted THIS install's database. Restoring it
     *   onto another install would suppress (or force) the automatic
     *   post-update re-sort there, which must be decided by that device's
     *   own sort history.
     */
    val excludedKeys: Set<String> =
        setOf(
            "show_balance",
            "onboarding_complete",
            "handled_otp_message_id",
            "schedule_send_tip_shown",
            "last_sorted_version_code",
        )
}

/**
 * Local backup and restore of the app's settings (the Preferences DataStore)
 * PLUS the user's own categorization rules, as a single JSON document - the
 * settings sibling of [BackupManager], which covers the database. Backups
 * are plain files the user controls; nothing ever leaves the device.
 *
 * Why rules ride along: a user's setup is their preferences (including the
 * pill order / hidden pills / labels, which are ordinary preferences in the
 * catalog) AND the rules they taught the app. One file restores both, so
 * nobody has to remember two exports.
 *
 * Only USER rules travel. Bundled rules ship with the APK and are reseeded
 * by [app.clearsms.data.rules.BundledRuleLoader]; a stale copy in a backup
 * would fight that reseed and could resurrect a rule a later version
 * deliberately changed. User and bundled rules are told apart by the
 * `source` column, which the code path that inserted the row set - never
 * by anything a file claims.
 *
 * Unlike the database restore, a document with a NEWER format version is not
 * rejected: settings are independent key/value pairs, so the recognised
 * entries are applied and the rest reported as skipped - the honest best
 * effort for a file from a future app version. Symmetrically, an older app
 * opening a format-2 file applies the settings it knows and ignores the
 * rules section, which it never looks at.
 */
class SettingsBackupManager(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
    private val appVersion: String,
    private val userRules: UserRuleStore,
    private val ruleImporter: RuleImporter,
) {
    /**
     * Serializes every set, non-excluded preference and every user rule to
     * [output] as JSON. The stream is not closed. Preferences still at their
     * defaults (never written) are omitted: restore then only touches what
     * the user changed.
     *
     * The rules section is the same [RuleDocument] shape the standalone
     * rule export/import uses, so a rule keeps its category, patterns and
     * extracts exactly and the same trust-boundary validation applies on
     * the way back in.
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
        val rules = userRules.userRules().filter { it.source == RuleSources.USER }
        val rulesDocument =
            RuleDocument(
                version = RULES_SECTION_VERSION,
                rules = rules.mapNotNull { it.toDefinition(json) },
            )
        val disabledRuleIds = rules.filter { !it.enabled }.map { it.id }.sorted()
        val document =
            buildJsonObject {
                put("type", DOCUMENT_TYPE)
                put("formatVersion", FORMAT_VERSION)
                put("appVersion", appVersion)
                put("createdAt", System.currentTimeMillis())
                put("settings", settings)
                put("rules", json.encodeToJsonElement(RuleDocument.serializer(), rulesDocument))
                put("disabledRuleIds", JsonArray(disabledRuleIds.map(::JsonPrimitive)))
            }
        json.encodeToStream(JsonObject.serializer(), document, output)
    }

    /**
     * Applies the settings backup read from [input].
     *
     * Safety properties:
     * - the ENTIRE document is decoded and every entry and every rule
     *   validated BEFORE any mutation, so an unparseable or non-settings
     *   file - or a corrupt/truncated/unsafe rules section - changes
     *   nothing at all, preferences included;
     * - unknown keys, excluded keys and wrong-typed values never throw -
     *   they are skipped and tallied in the returned [SettingsRestoreResult];
     * - rules land in one Room transaction and preferences in one
     *   [DataStore.edit], so each store is applied atomically; rules go
     *   first because a repeat of the rules step is idempotent (see below),
     *   so a failure between the two is recovered by simply restoring again;
     * - a rules-less file (format 1, app 0.20.0 and earlier) restores its
     *   preferences exactly as before and touches no rule.
     *
     * Rules are MERGED, never replaced: a restore adds the file's rules and
     * refreshes the ones it already knows, and rules the user created after
     * the backup was taken are left alone. Replacing would silently delete
     * those - a backup must never be the thing that loses a rule the user
     * still wants. The cost is that a rule deleted after the backup comes
     * back; the returned [SettingsRestoreResult.rules] count says how many
     * rules the file touched. Where a restored rule lands is decided by
     * [resolveRestoredRuleId]; bundled rows are never a target.
     *
     * The rules section is parsed as a rules document and never touches the
     * DataStore, so it cannot smuggle an excluded preference back in.
     *
     * @throws IllegalArgumentException when the stream is not a settings
     * backup (corrupt JSON, missing marker, or a database backup file), or
     * its rules section is not a valid rules document.
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

        // Validate the whole rules section before touching either store.
        val restoredRules = parseRulesSection(document["rules"])
        val rows =
            if (restoredRules.isEmpty()) {
                emptyList()
            } else {
                val existingUserIds = userRules.userRules().map { it.id }.toSet()
                val disabledIds =
                    (document["disabledRuleIds"] as? JsonArray)
                        .orEmpty()
                        .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                        .map { resolveRestoredRuleId(it, existingUserIds) }
                        .toSet()
                restoredRules.map { rule ->
                    val id = resolveRestoredRuleId(rule.id, existingUserIds)
                    rule.copy(
                        id = id,
                        // Never trust the file's provenance; see toUserEntity in BackupModels.
                        isUserDefined = true,
                        source = RuleSources.USER,
                        enabled = id !in disabledIds,
                    )
                }
            }

        if (rows.isNotEmpty()) {
            userRules.upsertUserRules(rows)
        }
        if (writes.isNotEmpty()) {
            dataStore.edit { prefs -> writes.forEach { it(prefs) } }
        }
        return SettingsRestoreResult(applied = writes.size, skipped = skipped, rules = rows.size)
    }

    /**
     * Turns the document's `rules` element into validated user rule rows.
     * Absent (a format-1 file) means no rules; anything else must be a
     * valid rules document, checked by [RuleImporter] - the same trust
     * boundary the standalone import uses (rule count, pattern length,
     * catastrophic-backtracking wrappers).
     */
    private fun parseRulesSection(element: JsonElement?): List<RuleEntity> {
        if (element == null || element is JsonNull) return emptyList()
        return try {
            ruleImporter.import(json.encodeToString(JsonElement.serializer(), element))
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Settings backup has an invalid rules section: ${e.message}", e)
        }
    }

    companion object {
        /** Marker distinguishing settings backups from database backups. */
        const val DOCUMENT_TYPE = "clearsms-settings"

        /**
         * Current settings backup document format. 1 = preferences only
         * (through app 0.20.0); 2 adds the `rules` and `disabledRuleIds`
         * sections. A format-1 file restores unchanged.
         */
        const val FORMAT_VERSION = 2

        /** Version stamp of the embedded rules document (the rules JSON schema). */
        const val RULES_SECTION_VERSION = "1.0"

        /**
         * Decides which row a restored rule with the file id [id] lands on.
         *
         * - If a USER rule with exactly that id already exists, it is
         *   updated in place - restoring onto the same device (or restoring
         *   twice) refreshes rules instead of duplicating them.
         * - Otherwise the id is namespaced into the `user:` space
         *   ([RuleEntity.namespacedUserId]) and inserted as a new user rule.
         *
         * Either way the target is a user row: bundled rule ids never carry
         * the `user:` prefix (a test over the bundled asset asserts it), so a
         * file naming a bundled id - by accident or by design - can never
         * overwrite that bundled row via the REPLACE insert strategy, and
         * never plants a user-sourced row under a bundled id that the next
         * reseed would then clobber.
         */
        internal fun resolveRestoredRuleId(
            id: String,
            existingUserIds: Set<String>,
        ): String = if (id in existingUserIds) id else RuleEntity.namespacedUserId(id)
    }
}
