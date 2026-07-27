package app.clearsms.work

import android.content.Context
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import app.clearsms.data.backup.BackupManager
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.prefs.SettingsRepositoryImpl
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.OtpAutoDeletePolicy
import app.clearsms.ui.common.BackupFrequency
import app.clearsms.ui.common.UiPrefs
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class BackupWorkerTest {
    private lateinit var context: Context
    private lateinit var db: ClearSmsDatabase
    private lateinit var uiPrefs: UiPrefs
    private lateinit var settings: SettingsRepositoryImpl
    private lateinit var settingsDataStore: DataStore<Preferences>

    private val internalBackup: File
        get() = File(File(context.filesDir, BackupWorker.BACKUP_DIR), BackupWorker.BACKUP_FILE_NAME)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Default test initialization: enqueued periodic work stays ENQUEUED
        // until its period is simulated, so scheduling can be asserted.
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        uiPrefs = UiPrefs(context)
        settingsDataStore =
            PreferenceDataStoreFactory.create {
                File.createTempFile("settings", ".preferences_pb")
            }
        settings = SettingsRepositoryImpl(settingsDataStore)
        internalBackup.delete()
    }

    @After
    fun tearDown() {
        db.close()
        internalBackup.delete()
    }

    private fun buildWorker(): BackupWorker {
        val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
        val manager = BackupManager(db, json)
        return TestListenableWorkerBuilder<BackupWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = BackupWorker(appContext, workerParameters, manager, uiPrefs, settings)
                },
            ).build()
    }

    private fun otpMessage(
        id: Long,
        timestamp: Long,
        body: String,
    ) = MessageEntity(
        id = id,
        threadId = 1L,
        sender = "AX-OTP",
        normalizedSender = "OTP",
        body = body,
        timestamp = timestamp,
        category = Category.OTP,
    )

    @Test
    fun `automatic backup is written to app-private internal storage, not external`() =
        runBlocking {
            uiPrefs.setBackupFrequency(BackupFrequency.DAILY)

            val result = buildWorker().doWork()

            assertThat(result).isEqualTo(ListenableWorker.Result.success())
            assertThat(internalBackup.exists()).isTrue()
            val legacyExternal =
                context.getExternalFilesDir(BackupWorker.BACKUP_DIR)?.let {
                    File(it, BackupWorker.BACKUP_FILE_NAME)
                }
            assertThat(legacyExternal?.exists() ?: false).isFalse()
        }

    @Test
    fun `legacy external plaintext export is deleted`() =
        runBlocking {
            uiPrefs.setBackupFrequency(BackupFrequency.DAILY)
            val legacyDir = context.getExternalFilesDir(BackupWorker.BACKUP_DIR)!!
            val legacy = File(legacyDir, BackupWorker.BACKUP_FILE_NAME)
            legacy.writeText("""{"old":"plaintext export"}""")

            buildWorker().doWork()

            assertThat(legacy.exists()).isFalse()
        }

    @Test
    fun `OFF frequency exports nothing and removes any previous automatic backup`() =
        runBlocking {
            uiPrefs.setBackupFrequency(BackupFrequency.OFF)
            internalBackup.parentFile!!.mkdirs()
            internalBackup.writeText("stale export")

            val result = buildWorker().doWork()

            assertThat(result).isEqualTo(ListenableWorker.Result.success())
            assertThat(internalBackup.exists()).isFalse()
        }

    @Test
    fun `applyFrequency OFF schedules nothing and cancels existing work`() {
        // OFF from a clean state: nothing gets scheduled.
        BackupWorker.applyFrequency(context, BackupFrequency.OFF)
        shadowOf(Looper.getMainLooper()).idle()
        val workManager = WorkManager.getInstance(context)
        assertThat(workManager.getWorkInfosForUniqueWork(BackupWorker.WORK_NAME).get()).isEmpty()

        // DAILY schedules the unique periodic work.
        BackupWorker.applyFrequency(context, BackupFrequency.DAILY)
        shadowOf(Looper.getMainLooper()).idle()
        var infos = workManager.getWorkInfosForUniqueWork(BackupWorker.WORK_NAME).get()
        assertThat(infos).hasSize(1)
        assertThat(infos.single().state).isNotEqualTo(WorkInfo.State.CANCELLED)

        // OFF cancels it again: nothing left in an active (unfinished) state.
        BackupWorker.applyFrequency(context, BackupFrequency.OFF)
        shadowOf(Looper.getMainLooper()).idle()
        infos = workManager.getWorkInfosForUniqueWork(BackupWorker.WORK_NAME).get()
        assertThat(infos.none { !it.state.isFinished }).isTrue()
    }

    @Test
    fun `automatic backup honors the OTP retention policy`() =
        runBlocking {
            uiPrefs.setBackupFrequency(BackupFrequency.DAILY)
            settings.setOtpAutoDeletePolicy(OtpAutoDeletePolicy.HOURS_24)
            val now = System.currentTimeMillis()
            db.messageDao().insertAll(
                listOf(
                    otpMessage(1, now - TimeUnit.DAYS.toMillis(2), "STALE-OTP-111111"),
                    otpMessage(2, now - TimeUnit.HOURS.toMillis(1), "FRESH-OTP-222222"),
                ),
            )

            buildWorker().doWork()

            val text = internalBackup.readText()
            assertThat(text).contains("FRESH-OTP-222222")
            assertThat(text).doesNotContain("STALE-OTP-111111")
        }

    @Test
    fun `NEVER policy keeps all OTP messages in the automatic backup`() =
        runBlocking {
            uiPrefs.setBackupFrequency(BackupFrequency.DAILY)
            settings.setOtpAutoDeletePolicy(OtpAutoDeletePolicy.NEVER)
            val now = System.currentTimeMillis()
            db.messageDao().insertAll(
                listOf(otpMessage(1, now - TimeUnit.DAYS.toMillis(30), "OLD-OTP-333333")),
            )

            buildWorker().doWork()

            assertThat(internalBackup.readText()).contains("OLD-OTP-333333")
        }
}
