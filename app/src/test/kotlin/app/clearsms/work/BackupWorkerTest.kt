package app.clearsms.work

import android.content.Context
import android.net.Uri
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
import app.clearsms.data.backup.SettingsBackupManager
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.prefs.SettingsRepositoryImpl
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.OtpAutoDeletePolicy
import app.clearsms.ui.common.BackupFrequency
import app.clearsms.ui.common.UiPrefs
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class BackupWorkerTest {
    private lateinit var context: Context
    private lateinit var db: ClearSmsDatabase
    private lateinit var uiPrefs: UiPrefs
    private lateinit var settings: SettingsRepositoryImpl
    private lateinit var settingsDataStore: DataStore<Preferences>
    private lateinit var fakeStore: FakeBackupDocumentStore

    /**
     * In-memory [BackupDocumentStore]: captures each written document by
     * name; [available] = false simulates a deleted directory / revoked
     * permission (openForWrite returns null, as the SAF impl does).
     */
    private class FakeBackupDocumentStore : BackupDocumentStore {
        var available = true
        val documents = mutableMapOf<String, ByteArrayOutputStream>()

        override fun openForWrite(fileName: String): OutputStream? {
            if (!available) return null
            // A fresh buffer per open mirrors the "wt" truncate semantics.
            return ByteArrayOutputStream().also { documents[fileName] = it }
        }
    }

    private val storeFactory =
        object : BackupDocumentStore.Factory {
            override fun create(treeUri: Uri): BackupDocumentStore = fakeStore
        }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        uiPrefs =
            UiPrefs(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("ui_settings", ".preferences_pb")
                },
            )
        settingsDataStore =
            PreferenceDataStoreFactory.create {
                File.createTempFile("settings", ".preferences_pb")
            }
        settings = SettingsRepositoryImpl(settingsDataStore)
        fakeStore = FakeBackupDocumentStore()
        runBlocking {
            uiPrefs.setBackupDirectoryUri("content://com.android.externalstorage.documents/tree/primary%3ABackups")
            uiPrefs.setBackupDirectoryError(false)
            uiPrefs.setLastAutoBackupMs(0L)
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun buildWorker(): BackupWorker {
        val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
        val manager = BackupManager(db, json)
        val settingsManager = SettingsBackupManager(settingsDataStore, json, "test")
        return TestListenableWorkerBuilder<BackupWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker =
                        BackupWorker(appContext, workerParameters, manager, settingsManager, uiPrefs, settings, storeFactory)
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
    fun `writes BOTH the messages and the settings backup into the chosen directory`() =
        runBlocking {
            uiPrefs.setBackupFrequency(BackupFrequency.DAILY)

            val result = buildWorker().doWork()

            assertThat(result).isEqualTo(ListenableWorker.Result.success())
            assertThat(fakeStore.documents.keys)
                .containsExactly(BackupWorker.MESSAGES_FILE_NAME, BackupWorker.SETTINGS_FILE_NAME)
            // Each document is the right export: the DB backup carries the
            // schema marker, the settings backup its own document marker.
            assertThat(fakeStore.documents.getValue(BackupWorker.MESSAGES_FILE_NAME).toString(Charsets.UTF_8.name()))
                .contains("\"messages\"")
            assertThat(fakeStore.documents.getValue(BackupWorker.SETTINGS_FILE_NAME).toString(Charsets.UTF_8.name()))
                .contains("\"settings\"")
        }

    @Test
    fun `no directory chosen fails the run and raises the settings warning flag`() =
        runBlocking {
            uiPrefs.setBackupFrequency(BackupFrequency.DAILY)
            uiPrefs.setBackupDirectoryUri(null)

            val result = buildWorker().doWork()

            assertThat(result).isEqualTo(ListenableWorker.Result.failure())
            assertThat(uiPrefs.backupDirectoryError.first()).isTrue()
            assertThat(fakeStore.documents).isEmpty()
        }

    @Test
    fun `directory gone at run time fails gracefully and raises the settings warning flag`() =
        runBlocking {
            uiPrefs.setBackupFrequency(BackupFrequency.DAILY)
            fakeStore.available = false

            val result = buildWorker().doWork()

            assertThat(result).isEqualTo(ListenableWorker.Result.failure())
            assertThat(uiPrefs.backupDirectoryError.first()).isTrue()
        }

    @Test
    fun `successful run clears a previously raised warning flag`() =
        runBlocking {
            uiPrefs.setBackupFrequency(BackupFrequency.DAILY)
            uiPrefs.setBackupDirectoryError(true)

            buildWorker().doWork()

            assertThat(uiPrefs.backupDirectoryError.first()).isFalse()
        }

    @Test
    fun `OFF frequency exports nothing`() =
        runBlocking {
            uiPrefs.setBackupFrequency(BackupFrequency.OFF)

            val result = buildWorker().doWork()

            assertThat(result).isEqualTo(ListenableWorker.Result.success())
            assertThat(fakeStore.documents).isEmpty()
        }

    @Test
    fun `WEEKLY skips the export while the last successful run is fresh`() =
        runBlocking {
            uiPrefs.setBackupFrequency(BackupFrequency.WEEKLY)
            uiPrefs.setLastAutoBackupMs(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1))

            val result = buildWorker().doWork()

            assertThat(result).isEqualTo(ListenableWorker.Result.success())
            assertThat(fakeStore.documents).isEmpty()

            // A stale last run exports again.
            uiPrefs.setLastAutoBackupMs(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8))
            buildWorker().doWork()
            assertThat(fakeStore.documents).isNotEmpty()
        }

    @Test
    fun `legacy app-private exports are deleted`() =
        runBlocking {
            uiPrefs.setBackupFrequency(BackupFrequency.DAILY)
            val internalLegacy =
                File(File(context.filesDir, BackupWorker.LEGACY_BACKUP_DIR), BackupWorker.LEGACY_BACKUP_FILE_NAME)
            internalLegacy.parentFile!!.mkdirs()
            internalLegacy.writeText("""{"old":"internal export"}""")
            val externalLegacy =
                File(context.getExternalFilesDir(BackupWorker.LEGACY_BACKUP_DIR)!!, BackupWorker.LEGACY_BACKUP_FILE_NAME)
            externalLegacy.writeText("""{"old":"plaintext export"}""")

            buildWorker().doWork()

            assertThat(internalLegacy.exists()).isFalse()
            assertThat(externalLegacy.exists()).isFalse()
        }

    @Test
    fun `applyFrequency OFF schedules nothing and cancels existing work`() {
        BackupWorker.applyFrequency(context, BackupFrequency.OFF)
        shadowOf(Looper.getMainLooper()).idle()
        val workManager = WorkManager.getInstance(context)
        assertThat(workManager.getWorkInfosForUniqueWork(BackupWorker.WORK_NAME).get()).isEmpty()

        BackupWorker.applyFrequency(context, BackupFrequency.DAILY)
        shadowOf(Looper.getMainLooper()).idle()
        var infos = workManager.getWorkInfosForUniqueWork(BackupWorker.WORK_NAME).get()
        assertThat(infos).hasSize(1)
        assertThat(infos.single().state).isNotEqualTo(WorkInfo.State.CANCELLED)

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

            val text = fakeStore.documents.getValue(BackupWorker.MESSAGES_FILE_NAME).toString(Charsets.UTF_8.name())
            assertThat(text).contains("FRESH-OTP-222222")
            assertThat(text).doesNotContain("STALE-OTP-111111")
        }
}
