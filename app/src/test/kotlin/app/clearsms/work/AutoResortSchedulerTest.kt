package app.clearsms.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import app.clearsms.testing.FakeSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Trigger contract of the automatic post-update re-sort: a version mismatch
 * enqueues exactly one AUTO-tagged run, a matching version enqueues nothing,
 * incomplete onboarding blocks it (never race the initial import), and a
 * queued/running initial import defers it entirely.
 */
@RunWith(RobolectricTestRunner::class)
class AutoResortSchedulerTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private val settings = FakeSettingsRepository()

    /** Any enqueued worker just parks, so states stay observable. */
    private class NeverFinishingWorker(
        appContext: Context,
        params: WorkerParameters,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result = awaitCancellation()
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config =
            Configuration
                .Builder()
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ): ListenableWorker = NeverFinishingWorker(appContext, workerParameters)
                    },
                ).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
    }

    private fun scheduler() = AutoResortScheduler(settings, workManager, CoroutineScope(Dispatchers.Unconfined))

    private fun sortInfos() = workManager.getWorkInfosForUniqueWork(RecategorizeWorker.WORK_NAME).get()

    @Test
    fun `version mismatch enqueues exactly one AUTO-tagged re-sort`() =
        runBlocking {
            settings.lastSortedVersionCode.value = 7
            settings.onboardingComplete.value = true

            scheduler().check(versionCode = 8)
            // A second cold-start check while the run is alive stays a no-op
            // (unique KEEP).
            scheduler().check(versionCode = 8)

            val infos = sortInfos()
            assertThat(infos).hasSize(1)
            assertThat(infos.single().tags).contains(RecategorizeWorker.TAG_AUTO)
        }

    @Test
    fun `matching version enqueues nothing`() =
        runBlocking {
            settings.lastSortedVersionCode.value = 8
            settings.onboardingComplete.value = true

            scheduler().check(versionCode = 8)

            assertThat(sortInfos()).isEmpty()
        }

    @Test
    fun `incomplete onboarding never triggers - the initial import owns the fresh pass`() =
        runBlocking {
            settings.lastSortedVersionCode.value = 0
            settings.onboardingComplete.value = false

            scheduler().check(versionCode = 8)

            assertThat(sortInfos()).isEmpty()
        }

    @Test
    fun `a queued or running initial import defers the auto re-sort`() =
        runBlocking {
            settings.lastSortedVersionCode.value = 0
            settings.onboardingComplete.value = true
            workManager.enqueueUniqueWork(
                InitialSyncWorker.WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<NeverFinishingWorker>().build(),
            )

            scheduler().check(versionCode = 8)

            assertThat(sortInfos()).isEmpty()
        }

    @Test
    fun `a never-sorted marker counts as a mismatch and self-heals older installs`() =
        runBlocking {
            settings.lastSortedVersionCode.value = 0
            settings.onboardingComplete.value = true

            scheduler().check(versionCode = 8)

            assertThat(sortInfos()).hasSize(1)
        }
}
