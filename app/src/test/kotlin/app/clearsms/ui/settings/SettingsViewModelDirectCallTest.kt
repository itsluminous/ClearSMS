package app.clearsms.ui.settings

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import app.cash.turbine.test
import app.clearsms.data.backup.BackupManager
import app.clearsms.data.backup.RoomUserRuleStore
import app.clearsms.data.backup.SettingsBackupManager
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.repository.SenderBlocker
import app.clearsms.data.repository.SenderMuter
import app.clearsms.data.rules.RuleImporter
import app.clearsms.testing.FakeMessageRepository
import app.clearsms.testing.FakeSettingsRepository
import app.clearsms.testing.InMemoryPreferencesDataStore
import app.clearsms.ui.common.UiPrefs
import app.clearsms.ui.composemsg.ContactSuggestions
import app.clearsms.ui.finance.BalanceVisibility
import app.clearsms.work.ReminderAlarmScheduler
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * The Direct call toggle's permission contract, on the REAL
 * [SettingsViewModel]: the stored preference follows the CALL_PHONE grant
 * read from the package manager - the one source of truth - and whenever
 * that read decides against the user's tap, the user is TOLD.
 *
 * Device verification found the gap this pins: denying the permission
 * dialog left the toggle off (correct) but showed nothing, because the
 * screen handed the dialog's `false` straight to [SettingsViewModel.setDirectCall],
 * which treats OFF as a plain, silent request. The request path now always
 * asks for ON and lets the ViewModel re-read the grant, so a denial lands
 * in the branch that emits [SettingsEvent.DirectCallDenied].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelDirectCallTest {
    private lateinit var context: Context
    private lateinit var db: ClearSmsDatabase
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val settings = FakeSettingsRepository()
    private val app get() = shadowOf(context as Application)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        WorkManager.getInstance(context).cancelAllWork().result.get()
        scope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(): SettingsViewModel {
        val json = Json { ignoreUnknownKeys = true }
        val messages = FakeMessageRepository()
        val senderMuter = SenderMuter(settings)
        return SettingsViewModel(
            context = context,
            settings = settings,
            uiPrefs = UiPrefs(InMemoryPreferencesDataStore()),
            messageRepository = messages,
            senderBlocker = SenderBlocker(settings, messages, senderMuter, InMemoryPreferencesDataStore(), scope),
            senderMuter = senderMuter,
            contactSuggestions = ContactSuggestions(context),
            backupManager = BackupManager(db, json),
            settingsBackupManager =
                SettingsBackupManager(
                    InMemoryPreferencesDataStore(),
                    json,
                    appVersion = "test",
                    userRules = RoomUserRuleStore(db),
                    ruleImporter = RuleImporter(json),
                ),
            workManager = WorkManager.getInstance(context),
            balanceVisibility = BalanceVisibility(),
            reminderAlarmScheduler = ReminderAlarmScheduler(context, db.reminderDao(), settings),
            ioDispatcher = dispatcher,
        )
    }

    // ------------------------------------------------------------------
    // The request path: the user tapped ON, the dialog answered.
    // ------------------------------------------------------------------

    @Test
    fun `a denied grant leaves the preference OFF and tells the user why`() =
        runTest(dispatcher) {
            app.denyPermissions(Manifest.permission.CALL_PHONE)
            val viewModel = viewModel()
            viewModel.eventFlow.test {
                // What the screen sends after the permission dialog, whatever
                // the dialog answered: the user's intent.
                viewModel.setDirectCall(true)
                advanceUntilIdle()
                assertThat(settings.directCall.value).isFalse()
                assertThat(awaitItem()).isEqualTo(SettingsEvent.DirectCallDenied)
                expectNoEvents()
            }
        }

    @Test
    fun `a granted request stores ON and says nothing - the switch moving IS the feedback`() =
        runTest(dispatcher) {
            app.grantPermissions(Manifest.permission.CALL_PHONE)
            val viewModel = viewModel()
            viewModel.eventFlow.test {
                viewModel.setDirectCall(true)
                advanceUntilIdle()
                assertThat(settings.directCall.value).isTrue()
                expectNoEvents()
            }
        }

    @Test
    fun `switching OFF needs no permission and is silent`() =
        runTest(dispatcher) {
            app.grantPermissions(Manifest.permission.CALL_PHONE)
            settings.directCall.value = true
            val viewModel = viewModel()
            viewModel.eventFlow.test {
                viewModel.setDirectCall(false)
                advanceUntilIdle()
                assertThat(settings.directCall.value).isFalse()
                expectNoEvents()
            }
        }

    /**
     * The screen must feed the ViewModel the user's INTENT after the
     * permission dialog, not the dialog's answer. `setDirectCall(granted)`
     * is exactly the bug device verification caught: a denial becomes a
     * plain OFF request, which the ViewModel rightly accepts in silence, and
     * [SettingsEvent.DirectCallDenied] - and its string - is never reached
     * from the only path that can produce a denial.
     */
    @Test
    fun `the permission-result callback asks for ON so the ViewModel's grant check decides and explains`() {
        val screen =
            File(
                listOf("src/main/kotlin/app/clearsms", "app/src/main/kotlin/app/clearsms").first { File(it).isDirectory },
                "ui/settings/SettingsScreen.kt",
            ).readText()
        val launcher =
            screen
                .substringAfter("rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())")
                .substringBefore("}")
        assertThat(launcher).contains("viewModel.setDirectCall(true)")
        assertThat(screen).doesNotContain("viewModel.setDirectCall(granted)")
    }
}
