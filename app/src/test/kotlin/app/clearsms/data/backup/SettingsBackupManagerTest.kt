package app.clearsms.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.clearsms.data.db.RuleEntity
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.data.prefs.SettingsRepositoryImpl
import app.clearsms.data.rules.RuleAction
import app.clearsms.data.rules.RuleDefinition
import app.clearsms.data.rules.RuleDocument
import app.clearsms.data.rules.RuleImporter
import app.clearsms.data.rules.RuleMatch
import app.clearsms.data.rules.RuleSources
import app.clearsms.data.rules.toDefinition
import app.clearsms.data.rules.toEntity
import app.clearsms.domain.model.DelayedSendDelay
import app.clearsms.domain.model.EnabledSections
import app.clearsms.domain.model.FinanceTab
import app.clearsms.domain.model.InboxPill
import app.clearsms.domain.model.LogoBackground
import app.clearsms.domain.model.MessageSortOrder
import app.clearsms.domain.model.NotificationAction
import app.clearsms.domain.model.OtpAutoDeletePolicy
import app.clearsms.domain.model.OtpDisplaySize
import app.clearsms.domain.model.StartDestination
import app.clearsms.domain.model.SwipeAction
import app.clearsms.domain.model.SwipeDeadZone
import app.clearsms.domain.model.ThemeMode
import app.clearsms.ui.alerts.AlertFilter
import app.clearsms.ui.conversation.MessageSelectionAction
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class SettingsBackupManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun newDataStore(name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) {
            tmp.newFile("$name.preferences_pb")
        }

    /**
     * In-memory [UserRuleStore] standing in for the rules table. It holds
     * BUNDLED rows too (seeded by [seedBundled]) so tests can prove the
     * backup never reads or writes them; [userRules] filters by source
     * exactly like the Room query does.
     */
    private class FakeUserRuleStore : UserRuleStore {
        val rows = linkedMapOf<String, RuleEntity>()
        var upserts = 0

        override suspend fun userRules(): List<RuleEntity> = rows.values.filter { it.source == RuleSources.USER }

        override suspend fun upsertUserRules(rules: List<RuleEntity>) {
            upserts++
            rules.forEach { rows[it.id] = it }
        }

        fun seedBundled(vararg ids: String) {
            ids.forEach { id ->
                rows[id] =
                    RuleEntity(
                        id = id,
                        name = "Bundled $id",
                        priority = 10,
                        matchJson = """{"sender_pattern":"BUNDLE"}""",
                        actionJson = """{"category":"important"}""",
                        isUserDefined = false,
                        source = RuleSources.BUILTIN,
                        createdAt = 1L,
                    )
            }
        }
    }

    private val ruleStore = FakeUserRuleStore()

    private fun manager(
        dataStore: DataStore<Preferences>,
        store: UserRuleStore = ruleStore,
    ) = SettingsBackupManager(dataStore, json, appVersion = "1.2.3-test", userRules = store, ruleImporter = RuleImporter(json))

    /** A synthetic user rule with every RuleDefinition field populated. */
    private fun userRule(
        id: String,
        category: String = "important",
        senderPattern: String = "^SYNTHBANK$",
        enabled: Boolean = true,
    ): RuleEntity =
        RuleDefinition(
            id = id,
            name = "Synthetic $id",
            priority = 900,
            match =
                RuleMatch(
                    senderPattern = senderPattern,
                    bodyPattern = "debited by INR ([0-9.,]+) on (\\d{2}-\\d{2}-\\d{4})",
                    bodyMustContain = listOf("debited"),
                    bodyMustNotContain = listOf("reversed"),
                    guardsNone = listOf("promo"),
                ),
            action =
                RuleAction(
                    category = category,
                    subCategory = "debit",
                    extract = mapOf("amount" to "$1", "date" to "$2"),
                    extractTypes = mapOf("date" to "date"),
                ),
            createdAt = "2026-01-02T03:04:05Z",
        ).toEntity(json, RuleSources.USER).copy(enabled = enabled)

    private fun RuleEntity.definition(): RuleDefinition = checkNotNull(toDefinition(json))

    /** Sets every backed-up preference to a value that differs from its default. */
    private suspend fun setAllNonDefaults(repo: SettingsRepositoryImpl) {
        repo.setTheme(ThemeMode.DARK)
        repo.setOtpAutoCopy(false)
        repo.setOtpAutoDeletePolicy(OtpAutoDeletePolicy.DAYS_3)
        repo.setOtpDisplaySize(OtpDisplaySize.OPTION_5)
        repo.setShowTransactionDetails(true)
        repo.setMessageSortOrder(MessageSortOrder.SENT)
        repo.setRecycleBinEnabled(true)
        repo.setDelayedSendEnabled(true)
        repo.setDelayedSendDelay(DelayedSendDelay.SECONDS_30)
        repo.setSignature("Sent from ClearSMS")
        repo.setShowRichAvatars(false)
        repo.setNotificationActions(setOf(NotificationAction.SHARE, NotificationAction.COPY_OTP))
        repo.setSwipeActionStart(SwipeAction.TOGGLE_READ)
        repo.setSwipeActionEnd(SwipeAction.NONE)
        repo.setSwipeDeadZone(SwipeDeadZone(enabled = true, centerXPercent = 30, widthPercent = 60, heightPercent = 50))
        repo.setDefaultDestination(StartDestination.FINANCE)
        // Two off, one explicitly on: all three keys land on disk while the
        // combination stays valid (all-off would be healed at read time).
        repo.setInboxSectionEnabled(false)
        repo.setFinanceSectionEnabled(false)
        repo.setAlertsSectionEnabled(true)
        repo.setDefaultInboxFilter(null)
        repo.setDefaultFinanceFilter(FinanceTab.CREDIT_CARDS)
        repo.setFinanceCurrency("CLP")
        repo.setTransactionNotifications(false)
        repo.setLogoBackground(LogoBackground.WHITE)
        repo.setInboxPillOrder(InboxPill.entries.reversed())
        repo.setInboxHiddenPills(setOf(InboxPill.UNKNOWN, InboxPill.SPAM))
        repo.setInboxUnreadToggle(false)
        repo.setFinancePillOrder(FinanceTab.entries.reversed())
        repo.setFinanceHiddenPills(setOf(FinanceTab.RECHARGES))
        repo.setAlertsPillOrder(AlertFilter.entries.reversed())
        repo.setAlertsHiddenPills(setOf(AlertFilter.DEPOSIT, AlertFilter.TRAVEL))
        repo.setMessageSelectionActionOrder(MessageSelectionAction.entries.reversed())
        repo.setBlockedKeywords(setOf("loan offer", "casino"))
        repo.setBlockedSenders(setOf("JIOPAY", "5551234567"))
        repo.setMutedSenders(setOf("PROMOCO", "5559876543"))
    }

    private suspend fun assertAllNonDefaults(repo: SettingsRepositoryImpl) {
        assertThat(repo.theme.first()).isEqualTo(ThemeMode.DARK)
        assertThat(repo.otpAutoCopy.first()).isFalse()
        assertThat(repo.otpAutoDeletePolicy.first()).isEqualTo(OtpAutoDeletePolicy.DAYS_3)
        assertThat(repo.otpDisplaySize.first()).isEqualTo(OtpDisplaySize.OPTION_5)
        assertThat(repo.showTransactionDetails.first()).isTrue()
        assertThat(repo.messageSortOrder.first()).isEqualTo(MessageSortOrder.SENT)
        assertThat(repo.recycleBinEnabled.first()).isTrue()
        assertThat(repo.delayedSendEnabled.first()).isTrue()
        assertThat(repo.delayedSendDelay.first()).isEqualTo(DelayedSendDelay.SECONDS_30)
        assertThat(repo.signature.first()).isEqualTo("Sent from ClearSMS")
        assertThat(repo.showRichAvatars.first()).isFalse()
        assertThat(repo.financeCurrency.first()).isEqualTo("CLP")
        assertThat(repo.notificationActions.first())
            .isEqualTo(setOf(NotificationAction.SHARE, NotificationAction.COPY_OTP))
        assertThat(repo.swipeActionStart.first()).isEqualTo(SwipeAction.TOGGLE_READ)
        assertThat(repo.swipeActionEnd.first()).isEqualTo(SwipeAction.NONE)
        assertThat(repo.swipeDeadZone.first())
            .isEqualTo(SwipeDeadZone(enabled = true, centerXPercent = 30, widthPercent = 60, heightPercent = 50))
        assertThat(repo.defaultDestination.first()).isEqualTo(StartDestination.FINANCE)
        assertThat(repo.enabledSections.first())
            .isEqualTo(EnabledSections(inbox = false, finance = false, alerts = true))
        assertThat(repo.defaultInboxFilter.first()).isNull()
        assertThat(repo.defaultFinanceFilter.first()).isEqualTo(FinanceTab.CREDIT_CARDS)
        assertThat(repo.transactionNotifications.first()).isFalse()
        assertThat(repo.logoBackground.first()).isEqualTo(LogoBackground.WHITE)
        assertThat(repo.inboxPillOrder.first()).isEqualTo(InboxPill.entries.reversed())
        assertThat(repo.inboxHiddenPills.first()).isEqualTo(setOf(InboxPill.UNKNOWN, InboxPill.SPAM))
        assertThat(repo.inboxUnreadToggle.first()).isFalse()
        assertThat(repo.financePillOrder.first()).isEqualTo(FinanceTab.entries.reversed())
        assertThat(repo.financeHiddenPills.first()).isEqualTo(setOf(FinanceTab.RECHARGES))
        assertThat(repo.alertsPillOrder.first()).isEqualTo(AlertFilter.entries.reversed())
        assertThat(repo.alertsHiddenPills.first()).isEqualTo(setOf(AlertFilter.DEPOSIT, AlertFilter.TRAVEL))
        assertThat(repo.messageSelectionActionOrder.first()).isEqualTo(MessageSelectionAction.entries.reversed())
        assertThat(repo.blockedKeywords.first()).isEqualTo(setOf("loan offer", "casino"))
        assertThat(repo.blockedSenders.first()).isEqualTo(setOf("JIOPAY", "5551234567"))
        assertThat(repo.mutedSenders.first()).isEqualTo(setOf("PROMOCO", "5559876543"))
    }

    private fun export(
        dataStore: DataStore<Preferences>,
        store: UserRuleStore = ruleStore,
    ): ByteArray =
        runBlocking {
            ByteArrayOutputStream().also { manager(dataStore, store).exportTo(it) }.toByteArray()
        }

    private fun exportedDocument(bytes: ByteArray): JsonObject = json.decodeFromString(JsonObject.serializer(), bytes.decodeToString())

    private fun exportedRules(bytes: ByteArray): RuleDocument =
        json.decodeFromJsonElement(RuleDocument.serializer(), checkNotNull(exportedDocument(bytes)["rules"]))

    private fun restore(
        dataStore: DataStore<Preferences>,
        text: String,
        store: UserRuleStore = ruleStore,
    ): SettingsRestoreResult = runBlocking { manager(dataStore, store).importFrom(ByteArrayInputStream(text.toByteArray())) }

    private fun exportedSettings(bytes: ByteArray): JsonObject {
        val document = json.decodeFromString(JsonObject.serializer(), bytes.decodeToString())
        return document["settings"] as JsonObject
    }

    @Test
    fun `every preference survives a backup and restore round trip`() =
        runBlocking {
            val source = newDataStore("source")
            setAllNonDefaults(SettingsRepositoryImpl(source))
            val bytes = export(source)

            val target = newDataStore("target")
            val result = manager(target).importFrom(ByteArrayInputStream(bytes))

            assertAllNonDefaults(SettingsRepositoryImpl(target))
            assertThat(result.applied).isEqualTo(SettingsBackupCatalog.entries.size)
            assertThat(result.skipped).isEqualTo(0)
        }

    /**
     * The forgotten-preference tripwire, in two independent halves:
     * 1. count: every setter on the [SettingsRepository] interface must be
     *    accounted for as either backed up or explicitly excluded, so adding
     *    a preference without touching the backup fails here;
     * 2. keys: after exercising every setter, every key physically present
     *    in the DataStore must be claimed by the catalog or the exclusion
     *    list - catching a stored name that drifted from the catalog's.
     */
    @Test
    fun `backup catalog covers every settings preference or excludes it explicitly`() =
        runBlocking {
            val setters =
                SettingsRepository::class
                    .members
                    .filter { it.name.startsWith("set") }
            assertThat(setters).hasSize(
                SettingsBackupCatalog.entries.size + SettingsBackupCatalog.excludedKeys.size,
            )

            val dataStore = newDataStore("coverage")
            val repo = SettingsRepositoryImpl(dataStore)
            setAllNonDefaults(repo)
            repo.setShowBalance(true)
            repo.setOnboardingComplete(true)
            repo.setHandledOtpMessageId(42L)
            repo.setScheduleSendTipShown(true)
            repo.setLastSortedVersionCode(3)

            val storedKeys =
                dataStore.data
                    .first()
                    .asMap()
                    .keys
                    .map { it.name }
            val claimed = SettingsBackupCatalog.byName.keys + SettingsBackupCatalog.excludedKeys
            assertThat(claimed).containsAtLeastElementsIn(storedKeys)
            // Every stored key came from exactly one setter, so sizes match too.
            assertThat(storedKeys).hasSize(setters.size)
        }

    @Test
    fun `excluded keys are never exported`() =
        runBlocking {
            val dataStore = newDataStore("excluded-export")
            val repo = SettingsRepositoryImpl(dataStore)
            setAllNonDefaults(repo)
            repo.setShowBalance(true)
            repo.setOnboardingComplete(true)
            repo.setHandledOtpMessageId(42L)
            repo.setScheduleSendTipShown(true)
            repo.setLastSortedVersionCode(3)

            val settings = exportedSettings(export(dataStore))
            SettingsBackupCatalog.excludedKeys.forEach { key ->
                assertThat(settings.keys).doesNotContain(key)
            }
            assertThat(settings.keys).hasSize(SettingsBackupCatalog.entries.size)
        }

    @Test
    fun `a crafted file cannot restore security-sensitive keys`() =
        runBlocking {
            val dataStore = newDataStore("excluded-import")
            val crafted =
                """
                {"type":"clearsms-settings","formatVersion":1,
                 "settings":{"show_balance":true,"onboarding_complete":true,
                             "handled_otp_message_id":42,"theme":"DARK"}}
                """.trimIndent()
            val result = manager(dataStore).importFrom(ByteArrayInputStream(crafted.toByteArray()))

            val repo = SettingsRepositoryImpl(dataStore)
            assertThat(repo.showBalance.first()).isFalse()
            assertThat(repo.onboardingComplete.first()).isFalse()
            assertThat(repo.handledOtpMessageId.first()).isEqualTo(0L)
            assertThat(repo.theme.first()).isEqualTo(ThemeMode.DARK)
            assertThat(result.applied).isEqualTo(1)
            assertThat(result.skipped).isEqualTo(3)
        }

    @Test
    fun `a restored backup applies show_transaction_details whichever way it was set`() =
        runBlocking {
            // The default is OFF and never persisted, so the only way this
            // key reaches a fresh install is an explicit choice - by toggle
            // or by restore. Both stored values must land verbatim.
            val on = newDataStore("details-on")
            val onFile =
                """
                {"type":"clearsms-settings","formatVersion":1,
                 "settings":{"show_transaction_details":true}}
                """.trimIndent()
            val onResult = manager(on).importFrom(ByteArrayInputStream(onFile.toByteArray()))
            assertThat(SettingsRepositoryImpl(on).showTransactionDetails.first()).isTrue()
            assertThat(onResult.applied).isEqualTo(1)

            val off = newDataStore("details-off")
            SettingsRepositoryImpl(off).setShowTransactionDetails(true)
            val offFile =
                """
                {"type":"clearsms-settings","formatVersion":1,
                 "settings":{"show_transaction_details":false}}
                """.trimIndent()
            val offResult = manager(off).importFrom(ByteArrayInputStream(offFile.toByteArray()))
            assertThat(SettingsRepositoryImpl(off).showTransactionDetails.first()).isFalse()
            assertThat(offResult.applied).isEqualTo(1)

            // A file that never mentions the key leaves the default alone.
            val untouched = newDataStore("details-untouched")
            manager(untouched).importFrom(ByteArrayInputStream("""{"type":"clearsms-settings","settings":{}}""".toByteArray()))
            assertThat(SettingsRepositoryImpl(untouched).showTransactionDetails.first()).isFalse()
        }

    @Test
    fun `unknown keys are skipped and counted, recognised ones still apply`() =
        runBlocking {
            val dataStore = newDataStore("unknown")
            val file =
                """
                {"type":"clearsms-settings","formatVersion":1,
                 "settings":{"theme":"DARK","some_future_setting":"whatever"}}
                """.trimIndent()
            val result = manager(dataStore).importFrom(ByteArrayInputStream(file.toByteArray()))

            assertThat(SettingsRepositoryImpl(dataStore).theme.first()).isEqualTo(ThemeMode.DARK)
            assertThat(result.applied).isEqualTo(1)
            assertThat(result.skipped).isEqualTo(1)
        }

    @Test
    fun `a backup from a development build carrying the dropped pill-labels key restores everything else`() =
        runBlocking {
            // Pill renaming existed only on an unmerged branch and was removed
            // before release, so there is no catalog entry and no migration:
            // its key is just another unknown entry - skipped, counted, and
            // never written to the DataStore.
            val dataStore = newDataStore("stale-labels")
            val file =
                """
                {"type":"clearsms-settings","formatVersion":1,
                 "settings":{"theme":"DARK","inbox_pill_order":"SPAM,OTP",
                             "inbox_pill_labels":"IMPORTANT=Bank\nSPAM=Junk"}}
                """.trimIndent()
            val result = manager(dataStore).importFrom(ByteArrayInputStream(file.toByteArray()))

            assertThat(result).isEqualTo(SettingsRestoreResult(applied = 2, skipped = 1, rules = 0))
            val repo = SettingsRepositoryImpl(dataStore)
            assertThat(repo.theme.first()).isEqualTo(ThemeMode.DARK)
            assertThat(repo.inboxPillOrder.first().take(2)).containsExactly(InboxPill.SPAM, InboxPill.OTP).inOrder()
            assertThat(
                dataStore.data
                    .first()
                    .asMap()
                    .keys
                    .map { it.name },
            ).doesNotContain("inbox_pill_labels")
        }

    @Test
    fun `wrong-typed values are skipped and counted, never applied`() =
        runBlocking {
            val dataStore = newDataStore("wrong-type")
            val file =
                """
                {"type":"clearsms-settings","formatVersion":1,
                 "settings":{"theme":true,"otp_auto_copy":"yes",
                             "notification_actions":"REPLY","signature":"ok"}}
                """.trimIndent()
            val result = manager(dataStore).importFrom(ByteArrayInputStream(file.toByteArray()))

            val repo = SettingsRepositoryImpl(dataStore)
            assertThat(repo.theme.first()).isEqualTo(ThemeMode.SYSTEM)
            assertThat(repo.otpAutoCopy.first()).isTrue()
            assertThat(repo.signature.first()).isEqualTo("ok")
            assertThat(result.applied).isEqualTo(1)
            assertThat(result.skipped).isEqualTo(3)
        }

    @Test
    fun `corrupt JSON throws cleanly and applies nothing`() {
        val dataStore = newDataStore("corrupt")
        runBlocking { SettingsRepositoryImpl(dataStore).setTheme(ThemeMode.DARK) }

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                manager(dataStore).importFrom(ByteArrayInputStream("{not json".toByteArray()))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                manager(dataStore).importFrom(ByteArrayInputStream(ByteArray(0)))
            }
        }
        // The pre-existing preference is untouched.
        runBlocking {
            assertThat(SettingsRepositoryImpl(dataStore).theme.first()).isEqualTo(ThemeMode.DARK)
        }
    }

    @Test
    fun `a database backup file is rejected, not silently half-applied`() {
        val dataStore = newDataStore("db-file")
        val dbBackup = """{"formatVersion":1,"createdAt":1,"messages":[]}"""
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                manager(dataStore).importFrom(ByteArrayInputStream(dbBackup.toByteArray()))
            }
        }
    }

    @Test
    fun `a newer format is applied best-effort instead of rejected`() =
        runBlocking {
            val dataStore = newDataStore("newer")
            val file =
                """
                {"type":"clearsms-settings","formatVersion":99,"appVersion":"9.9.9",
                 "settings":{"theme":"LIGHT","brand_new_pref":123}}
                """.trimIndent()
            val result = manager(dataStore).importFrom(ByteArrayInputStream(file.toByteArray()))

            assertThat(SettingsRepositoryImpl(dataStore).theme.first()).isEqualTo(ThemeMode.LIGHT)
            assertThat(result.applied).isEqualTo(1)
            assertThat(result.skipped).isEqualTo(1)
        }

    @Test
    fun `never-set preferences are omitted from the export`() =
        runBlocking<Unit> {
            val dataStore = newDataStore("sparse")
            SettingsRepositoryImpl(dataStore).setTheme(ThemeMode.DARK)

            val bytes = export(dataStore)
            val settings = exportedSettings(bytes)
            assertThat(settings.keys).containsExactly("theme")

            // And restoring that sparse file only touches what it names.
            val target = newDataStore("sparse-target")
            val result = manager(target).importFrom(ByteArrayInputStream(bytes))
            assertThat(result.applied).isEqualTo(1)
            assertThat(result.skipped).isEqualTo(0)
            assertThat(
                target.data
                    .first()
                    .asMap()
                    .keys
                    .map { it.name },
            ).containsExactly("theme")
        }

    @Test
    fun `export carries provenance - type, format version and app version`() =
        runBlocking {
            val dataStore = newDataStore("provenance")
            SettingsRepositoryImpl(dataStore).setTheme(ThemeMode.DARK)
            val document = json.decodeFromString(JsonObject.serializer(), export(dataStore).decodeToString())
            assertThat(document["type"]?.toString()).isEqualTo("\"clearsms-settings\"")
            assertThat(document["formatVersion"]?.toString()).isEqualTo("2")
            assertThat(document["appVersion"]?.toString()).isEqualTo("\"1.2.3-test\"")
            assertThat(document.keys).contains("createdAt")
        }

    @Test
    fun `restore applies atomically - a stale pill order and theme land together`() =
        runBlocking<Unit> {
            // Regression guard for the "apply in one edit" property: both
            // writes must be visible in the same first emission.
            val dataStore = newDataStore("atomic")
            val file =
                """
                {"type":"clearsms-settings","formatVersion":1,
                 "settings":{"theme":"DARK","inbox_pill_order":"OTP,PERSONAL"}}
                """.trimIndent()
            manager(dataStore).importFrom(ByteArrayInputStream(file.toByteArray()))

            val prefs =
                dataStore.data
                    .first()
                    .asMap()
                    .mapKeys { it.key.name }
            assertThat(prefs["theme"]).isEqualTo("DARK")
            assertThat(prefs["inbox_pill_order"]).isEqualTo("OTP,PERSONAL")
            // The lenient pill-order reader completes the stale list.
            val order = SettingsRepositoryImpl(dataStore).inboxPillOrder.first()
            assertThat(order.take(2)).isEqualTo(listOf(InboxPill.OTP, InboxPill.PERSONAL))
            assertThat(order).containsExactlyElementsIn(InboxPill.entries)
        }

    // ---- rules section -------------------------------------------------

    @Test
    fun `user rules and the pill customisation round-trip with the preferences`() =
        runBlocking {
            val source = newDataStore("rules-source")
            setAllNonDefaults(SettingsRepositoryImpl(source))
            ruleStore.seedBundled("generic-scam-01", "hdfc-debit-01")
            ruleStore.upsertUserRules(listOf(userRule("user_aaaa1111"), userRule("user_bbbb2222", category = "spam")))
            val bytes = export(source)

            // The rules section is the standalone rules-document shape and
            // carries every user rule with its fields intact.
            val exported = exportedRules(bytes)
            assertThat(exported.version).isEqualTo(SettingsBackupManager.RULES_SECTION_VERSION)
            assertThat(exported.rules.map { it.id }).containsExactly("user_aaaa1111", "user_bbbb2222")
            assertThat(exported.rules.first { it.id == "user_aaaa1111" })
                .isEqualTo(ruleStore.rows.getValue("user_aaaa1111").definition())

            val target = newDataStore("rules-target")
            val targetStore = FakeUserRuleStore().apply { seedBundled("generic-scam-01", "hdfc-debit-01") }
            val result = manager(target, targetStore).importFrom(ByteArrayInputStream(bytes))

            assertAllNonDefaults(SettingsRepositoryImpl(target))
            assertThat(result.applied).isEqualTo(SettingsBackupCatalog.entries.size)
            assertThat(result.skipped).isEqualTo(0)
            assertThat(result.rules).isEqualTo(2)
            // Fresh device: no user rule had those ids, so they land in the
            // user namespace, as user rules, with category/pattern/extracts
            // exactly as exported.
            val restored = targetStore.userRules().associateBy { it.id }
            assertThat(restored.keys).containsExactly("user:user_aaaa1111", "user:user_bbbb2222")
            restored.values.forEach {
                assertThat(it.source).isEqualTo(RuleSources.USER)
                assertThat(it.isUserDefined).isTrue()
                assertThat(it.enabled).isTrue()
            }
            val original = ruleStore.rows.getValue("user_aaaa1111").definition()
            val copy = restored.getValue("user:user_aaaa1111").definition()
            assertThat(copy.match).isEqualTo(original.match)
            assertThat(copy.action).isEqualTo(original.action)
            assertThat(copy.action.category).isEqualTo("important")
            assertThat(copy.action.extract).containsExactly("amount", "$1", "date", "$2")
            assertThat(copy.priority).isEqualTo(900)
            assertThat(copy.name).isEqualTo("Synthetic user_aaaa1111")
            assertThat(restored.getValue("user:user_aaaa1111").createdAt)
                .isEqualTo(ruleStore.rows.getValue("user_aaaa1111").createdAt)
            assertThat(
                restored
                    .getValue("user:user_bbbb2222")
                    .definition()
                    .action.category,
            ).isEqualTo("spam")
            // Bundled rows on the target are byte-for-byte what they were.
            assertThat(targetStore.rows.getValue("generic-scam-01").source).isEqualTo(RuleSources.BUILTIN)
            assertThat(targetStore.rows.getValue("hdfc-debit-01").name).isEqualTo("Bundled hdfc-debit-01")
        }

    @Test
    fun `bundled rules are never exported - only source=user rows leave the device`() =
        runBlocking<Unit> {
            val dataStore = newDataStore("bundled-export")
            ruleStore.seedBundled("generic-scam-01", "hdfc-debit-01", "meesho-otp-01")
            ruleStore.upsertUserRules(listOf(userRule("user_cccc3333")))
            // A community row is not a user row either.
            ruleStore.rows["community-x-01"] =
                userRule("community-x-01").copy(isUserDefined = false, source = RuleSources.COMMUNITY)

            val exported = exportedRules(export(dataStore))

            assertThat(exported.rules.map { it.id }).containsExactly("user_cccc3333")
        }

    @Test
    fun `no bundled rule id lives in the user namespace, so a namespaced restore can never hit one`() {
        val asset =
            listOf(File("src/main/assets/default_rules.json"), File("app/src/main/assets/default_rules.json"))
                .first { it.exists() }
        val bundled = json.decodeFromString(RuleDocument.serializer(), asset.readText())
        assertThat(bundled.rules).isNotEmpty()
        bundled.rules.forEach { rule ->
            assertThat(rule.id).doesNotContain(RuleEntity.USER_ID_PREFIX)
            assertThat(SettingsBackupManager.resolveRestoredRuleId(rule.id, emptySet())).isNotEqualTo(rule.id)
        }
    }

    @Test
    fun `a restored rule whose id is a bundled rule id is namespaced, the bundled row is untouched`() =
        runBlocking {
            val dataStore = newDataStore("bundled-collision")
            ruleStore.seedBundled("generic-scam-01")
            val bundledBefore = ruleStore.rows.getValue("generic-scam-01")
            val crafted =
                """
                {"type":"clearsms-settings","formatVersion":2,"settings":{},
                 "rules":{"version":"1.0","rules":[
                   {"id":"generic-scam-01","priority":999,
                    "match":{"sender_pattern":"^EVIL$"},"action":{"category":"personal"}}]}}
                """.trimIndent()

            val result = restore(dataStore, crafted)

            assertThat(result.rules).isEqualTo(1)
            assertThat(ruleStore.rows.getValue("generic-scam-01")).isEqualTo(bundledBefore)
            val planted = ruleStore.rows.getValue("user:generic-scam-01")
            assertThat(planted.source).isEqualTo(RuleSources.USER)
            assertThat(planted.isUserDefined).isTrue()
            assertThat(planted.definition().action.category).isEqualTo("personal")
        }

    @Test
    fun `restore merges - same-id user rules refresh in place, later rules survive, nothing is deleted`() =
        runBlocking {
            val dataStore = newDataStore("merge")
            ruleStore.seedBundled("hdfc-debit-01")
            // On the device: an older copy of rule A, plus rule B created
            // AFTER the backup was taken.
            ruleStore.upsertUserRules(
                listOf(
                    userRule("user_aaaa1111", category = "promotional"),
                    userRule("user_bbbb2222", category = "personal"),
                ),
            )
            val fileRuleA = userRule("user_aaaa1111", category = "important").definition()
            val fileRuleC = userRule("user_cccc3333", category = "spam").definition()
            val file =
                buildDocument(
                    RuleDocument(version = "1.0", rules = listOf(fileRuleA, fileRuleC)),
                    settings = """{"theme":"DARK"}""",
                )

            val result = restore(dataStore, file)

            assertThat(result.rules).isEqualTo(2)
            assertThat(result.applied).isEqualTo(1)
            val user = ruleStore.userRules().associateBy { it.id }
            // A: existing user id -> updated in place under the SAME id.
            assertThat(
                user
                    .getValue("user_aaaa1111")
                    .definition()
                    .action.category,
            ).isEqualTo("important")
            assertThat(user.keys).doesNotContain("user:user_aaaa1111")
            // B: created after the backup -> untouched.
            assertThat(
                user
                    .getValue("user_bbbb2222")
                    .definition()
                    .action.category,
            ).isEqualTo("personal")
            // C: new to this device -> added in the user namespace.
            assertThat(
                user
                    .getValue("user:user_cccc3333")
                    .definition()
                    .action.category,
            ).isEqualTo("spam")
            assertThat(user.keys).hasSize(3)
            assertThat(ruleStore.rows.getValue("hdfc-debit-01").source).isEqualTo(RuleSources.BUILTIN)
            assertThat(SettingsRepositoryImpl(dataStore).theme.first()).isEqualTo(ThemeMode.DARK)
        }

    @Test
    fun `restoring the same file twice is idempotent - no duplicate rules`() =
        runBlocking<Unit> {
            val dataStore = newDataStore("idempotent")
            val file = buildDocument(RuleDocument(version = "1.0", rules = listOf(userRule("user_dddd4444").definition())))

            restore(dataStore, file)
            val afterFirst = ruleStore.rows.toMap()
            restore(dataStore, file)

            assertThat(ruleStore.rows).isEqualTo(afterFirst)
            assertThat(ruleStore.rows.keys).containsExactly("user:user_dddd4444")
        }

    @Test
    fun `disabled user rules stay disabled across the round trip`() =
        runBlocking {
            val dataStore = newDataStore("disabled")
            ruleStore.upsertUserRules(listOf(userRule("user_on"), userRule("user_off", enabled = false)))
            val bytes = export(dataStore)
            val disabled = exportedDocument(bytes)["disabledRuleIds"] as JsonArray
            assertThat(disabled.map { (it as JsonPrimitive).content }).containsExactly("user_off")

            val targetStore = FakeUserRuleStore()
            manager(newDataStore("disabled-target"), targetStore).importFrom(ByteArrayInputStream(bytes))

            assertThat(targetStore.rows.getValue("user:user_on").enabled).isTrue()
            assertThat(targetStore.rows.getValue("user:user_off").enabled).isFalse()
        }

    @Test
    fun `a 0-20-0 preferences-only backup (format 1, no rules) restores cleanly`() =
        runBlocking {
            val dataStore = newDataStore("legacy")
            ruleStore.seedBundled("generic-scam-01")
            ruleStore.upsertUserRules(listOf(userRule("user_keep")))
            val before = ruleStore.rows.toMap()
            val upsertsBefore = ruleStore.upserts
            val legacy =
                """
                {"type":"clearsms-settings","formatVersion":1,"appVersion":"0.20.0","createdAt":1,
                 "settings":{"theme":"DARK","inbox_pill_order":"OTP,PERSONAL","blocked_senders":["JIOPAY"]}}
                """.trimIndent()

            val result = restore(dataStore, legacy)

            assertThat(result).isEqualTo(SettingsRestoreResult(applied = 3, skipped = 0, rules = 0))
            assertThat(SettingsRepositoryImpl(dataStore).theme.first()).isEqualTo(ThemeMode.DARK)
            assertThat(ruleStore.rows).isEqualTo(before)
            assertThat(ruleStore.upserts).isEqualTo(upsertsBefore)
        }

    @Test
    fun `a corrupt, truncated or unsafe rules section rejects the WHOLE file - no rule and no preference applied`() {
        val dataStore = newDataStore("bad-rules")
        runBlocking { SettingsRepositoryImpl(dataStore).setTheme(ThemeMode.LIGHT) }
        ruleStore.seedBundled("generic-scam-01")
        runBlocking { ruleStore.upsertUserRules(listOf(userRule("user_keep"))) }
        val before = ruleStore.rows.toMap()
        val upsertsBefore = ruleStore.upserts
        val prefix = """{"type":"clearsms-settings","formatVersion":2,"settings":{"theme":"DARK"},"""
        val badSections =
            listOf(
                // wrong type
                """"rules":"not a rules document"}""",
                // rules array holding a non-rule (missing action)
                """"rules":{"version":"1.0","rules":[{"id":"x"}]}}""",
                // rule that fails the importer's safety validation (ReDoS wrapper)
                """"rules":{"version":"1.0","rules":[{"id":"x","match":{"body_pattern":".*loan"},"action":{"category":"spam"}}]}}""",
                // truncated mid-rule
                """"rules":{"version":"1.0","rules":[{"id":"x","match":{"body_pat""",
            )
        for (section in badSections) {
            assertThrows(IllegalArgumentException::class.java) { restore(dataStore, prefix + section) }
        }
        runBlocking {
            assertThat(SettingsRepositoryImpl(dataStore).theme.first()).isEqualTo(ThemeMode.LIGHT)
        }
        assertThat(ruleStore.rows).isEqualTo(before)
        assertThat(ruleStore.upserts).isEqualTo(upsertsBefore)
    }

    @Test
    fun `the rules section cannot smuggle an excluded preference back in`() =
        runBlocking {
            val dataStore = newDataStore("smuggle")
            val crafted =
                """
                {"type":"clearsms-settings","formatVersion":2,
                 "settings":{"theme":"DARK"},
                 "rules":{"version":"1.0","show_balance":true,"settings":{"show_balance":true},
                          "rules":[{"id":"show_balance","match":{"sender_pattern":"^X$"},
                                    "action":{"category":"important","extract":{"show_balance":"true"}}}]},
                 "disabledRuleIds":["show_balance", 42, {"show_balance":true}]}
                """.trimIndent()

            val result = restore(dataStore, crafted)

            val repo = SettingsRepositoryImpl(dataStore)
            assertThat(repo.showBalance.first()).isFalse()
            assertThat(repo.theme.first()).isEqualTo(ThemeMode.DARK)
            assertThat(
                dataStore.data
                    .first()
                    .asMap()
                    .keys
                    .map { it.name },
            ).containsExactly("theme")
            assertThat(result).isEqualTo(SettingsRestoreResult(applied = 1, skipped = 0, rules = 1))
            // It only ever became a (disabled) user rule in the user namespace.
            val planted = ruleStore.rows.getValue("user:show_balance")
            assertThat(planted.source).isEqualTo(RuleSources.USER)
            assertThat(planted.enabled).isFalse()
        }

    @Test
    fun `a file claiming builtin provenance is still restored as user rules`() =
        runBlocking {
            val dataStore = newDataStore("provenance-claim")
            val crafted =
                """
                {"type":"clearsms-settings","formatVersion":2,"settings":{},
                 "rules":{"version":"1.0","rules":[
                   {"id":"hdfc-debit-01","source":"builtin","isUserDefined":false,
                    "match":{"sender_pattern":"^HDFCBK$"},"action":{"category":"important"}}]}}
                """.trimIndent()

            restore(dataStore, crafted)

            assertThat(ruleStore.rows.keys).containsExactly("user:hdfc-debit-01")
            assertThat(ruleStore.rows.getValue("user:hdfc-debit-01").source).isEqualTo(RuleSources.USER)
        }

    /**
     * Coverage tripwire for the rule payload, the sibling of the preference
     * one: every column of [RuleEntity] must be either carried by the rules
     * section (through [RuleDefinition]) or explicitly forced on restore, so
     * a new column can never be silently dropped from the backup.
     */
    @Test
    fun `every RuleEntity column is either carried in the rules section or explicitly forced on restore`() {
        val columns =
            RuleEntity::class
                .constructors
                .first()
                .parameters
                .map { checkNotNull(it.name) }
        val carried = setOf("id", "name", "priority", "matchJson", "actionJson", "createdAt", "enabled")
        val forced = setOf("source", "isUserDefined")
        assertThat(carried.intersect(forced)).isEmpty()
        assertThat(carried + forced).containsExactlyElementsIn(columns)

        // And the carried ones really do survive: every field of a fully
        // populated rule is equal after export -> import on a fresh store.
        val rule = userRule("user_full", enabled = false)
        runBlocking { ruleStore.upsertUserRules(listOf(rule)) }
        val bytes = export(newDataStore("rule-coverage"))
        val target = FakeUserRuleStore()
        runBlocking { manager(newDataStore("rule-coverage-target"), target).importFrom(ByteArrayInputStream(bytes)) }
        val restored = target.rows.getValue("user:user_full")
        assertThat(restored.copy(id = rule.id)).isEqualTo(rule)
    }

    private fun buildDocument(
        rules: RuleDocument,
        settings: String = "{}",
    ): String =
        """{"type":"clearsms-settings","formatVersion":2,"settings":$settings,""" +
            """"rules":${json.encodeToString(RuleDocument.serializer(), rules)}}"""
}
