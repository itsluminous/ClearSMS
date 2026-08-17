package app.clearsms

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.clearsms.data.repository.UndoManager
import app.clearsms.work.AutoResortScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Implements [Configuration.Provider] so WorkManager is initialized on demand with a
 * Hilt-aware worker factory (the default initializer is removed in the manifest).
 */
@HiltAndroidApp
class ClearSmsApplication :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var undoManager: UndoManager

    @Inject
    lateinit var autoResortScheduler: AutoResortScheduler

    override fun onCreate() {
        super.onCreate()
        // Commits any deferred provider deletion that survived process death
        // (a deleted message must never resurrect in other SMS apps) and
        // purges recycle-bin rows past their 30-day retention.
        undoManager.onAppStart()
        // First cold start of a new app version: re-sort the whole database
        // with the updated rules automatically (Settings → Sort inbox again
        // without the user having to remember it).
        autoResortScheduler.onAppStart()
    }

    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .build()
}
