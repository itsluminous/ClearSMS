package app.clearsms.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.data.prefs.SettingsRepositoryImpl
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.FinanceTab
import app.clearsms.domain.model.LogoBackground
import app.clearsms.domain.model.NotificationAction
import app.clearsms.domain.model.OtpAutoDeletePolicy
import app.clearsms.domain.model.OtpDisplaySize
import app.clearsms.domain.model.StartDestination
import app.clearsms.domain.model.SwipeAction
import app.clearsms.domain.model.ThemeMode
import app.clearsms.ui.alerts.AlertFilter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

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

    private fun manager(dataStore: DataStore<Preferences>) = SettingsBackupManager(dataStore, json, appVersion = "1.2.3-test")

    /** Sets every backed-up preference to a value that differs from its default. */
    private suspend fun setAllNonDefaults(repo: SettingsRepositoryImpl) {
        repo.setTheme(ThemeMode.DARK)
        repo.setOtpAutoCopy(false)
        repo.setOtpAutoDeletePolicy(OtpAutoDeletePolicy.DAYS_3)
        repo.setOtpDisplaySize(OtpDisplaySize.OPTION_5)
        repo.setShowTransactionDetails(false)
        repo.setRecycleBinEnabled(true)
        repo.setSignature("Sent from ClearSMS")
        repo.setShowRichAvatars(false)
        repo.setNotificationActions(setOf(NotificationAction.SHARE, NotificationAction.COPY_OTP))
        repo.setSwipeActionStart(SwipeAction.TOGGLE_READ)
        repo.setSwipeActionEnd(SwipeAction.NONE)
        repo.setDefaultDestination(StartDestination.FINANCE)
        repo.setDefaultInboxFilter(null)
        repo.setDefaultFinanceFilter(FinanceTab.CREDIT_CARDS)
        repo.setTransactionNotifications(false)
        repo.setLogoBackground(LogoBackground.WHITE)
        repo.setInboxPillOrder(Category.entries.reversed())
        repo.setFinancePillOrder(FinanceTab.entries.reversed())
        repo.setAlertsPillOrder(AlertFilter.entries.reversed())
        repo.setBlockedKeywords(setOf("loan offer", "casino"))
    }

    private suspend fun assertAllNonDefaults(repo: SettingsRepositoryImpl) {
        assertThat(repo.theme.first()).isEqualTo(ThemeMode.DARK)
        assertThat(repo.otpAutoCopy.first()).isFalse()
        assertThat(repo.otpAutoDeletePolicy.first()).isEqualTo(OtpAutoDeletePolicy.DAYS_3)
        assertThat(repo.otpDisplaySize.first()).isEqualTo(OtpDisplaySize.OPTION_5)
        assertThat(repo.showTransactionDetails.first()).isFalse()
        assertThat(repo.recycleBinEnabled.first()).isTrue()
        assertThat(repo.signature.first()).isEqualTo("Sent from ClearSMS")
        assertThat(repo.showRichAvatars.first()).isFalse()
        assertThat(repo.notificationActions.first())
            .isEqualTo(setOf(NotificationAction.SHARE, NotificationAction.COPY_OTP))
        assertThat(repo.swipeActionStart.first()).isEqualTo(SwipeAction.TOGGLE_READ)
        assertThat(repo.swipeActionEnd.first()).isEqualTo(SwipeAction.NONE)
        assertThat(repo.defaultDestination.first()).isEqualTo(StartDestination.FINANCE)
        assertThat(repo.defaultInboxFilter.first()).isNull()
        assertThat(repo.defaultFinanceFilter.first()).isEqualTo(FinanceTab.CREDIT_CARDS)
        assertThat(repo.transactionNotifications.first()).isFalse()
        assertThat(repo.logoBackground.first()).isEqualTo(LogoBackground.WHITE)
        assertThat(repo.inboxPillOrder.first()).isEqualTo(Category.entries.reversed())
        assertThat(repo.financePillOrder.first()).isEqualTo(FinanceTab.entries.reversed())
        assertThat(repo.alertsPillOrder.first()).isEqualTo(AlertFilter.entries.reversed())
        assertThat(repo.blockedKeywords.first()).isEqualTo(setOf("loan offer", "casino"))
    }

    private fun export(dataStore: DataStore<Preferences>): ByteArray =
        runBlocking {
            ByteArrayOutputStream().also { manager(dataStore).exportTo(it) }.toByteArray()
        }

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
            assertThat(document["formatVersion"]?.toString()).isEqualTo("1")
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
            assertThat(order.take(2)).isEqualTo(listOf(Category.OTP, Category.PERSONAL))
            assertThat(order).containsExactlyElementsIn(Category.entries)
        }
}
