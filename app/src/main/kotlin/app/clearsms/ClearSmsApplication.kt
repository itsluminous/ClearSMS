package app.clearsms

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.clearsms.data.repository.SenderBlocker
import app.clearsms.data.repository.UndoManager
import app.clearsms.diagnostics.Diag
import app.clearsms.diagnostics.DiagCrashHandler
import app.clearsms.diagnostics.DiagFileSink
import app.clearsms.diagnostics.DiagLevel
import app.clearsms.shortcuts.ConversationShortcutPublisher
import app.clearsms.sms.SenderRegion
import app.clearsms.work.AutoResortScheduler
import app.clearsms.work.SimBackfillWorker
import dagger.hilt.android.HiltAndroidApp
import java.io.File
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

    @Inject
    lateinit var senderBlocker: SenderBlocker

    @Inject
    lateinit var conversationShortcutPublisher: ConversationShortcutPublisher

    override fun onCreate() {
        super.onCreate()
        installDiagnostics()
        // The region every sender key is computed under (issue #42): from
        // the SIM, before anything normalizes a sender.
        SenderRegion.install(this)
        // Commits any deferred provider deletion that survived process death
        // (a deleted message must never resurrect in other SMS apps) and
        // purges recycle-bin rows past their 30-day retention.
        undoManager.onAppStart()
        // First cold start of a new app version: re-sort the whole database
        // with the updated rules automatically (Settings → Sort inbox again
        // without the user having to remember it).
        autoResortScheduler.onAppStart()
        // Folds legacy block records (the old ui-prefs mirror and per-row
        // flags) into the authoritative settings blocklist - idempotent.
        senderBlocker.onAppStart()
        // Launcher shortcuts (issue #81): a process-lifetime observer of the
        // inbox's threads keeps the long-press menu equal to the pinned and
        // recent conversations - started here, not in the activity, so a
        // message received in the background (which starts this process)
        // refreshes it too. No-op below API 25.
        conversationShortcutPublisher.start()
        // One-time provider backfills (SIM, then sent time): fill columns on
        // rows imported before the importer read them. Instant no-op once
        // the versioned passes have completed.
        SimBackfillWorker.enqueue(this)
    }

    /**
     * In-app diagnostic log (Settings → About → Share diagnostic logs):
     * restores the rotating file into the ring buffer, echoes entries to
     * logcat for developers, and hooks uncaught exceptions - chaining to the
     * platform's handler, so a crash is still a crash. First thing in
     * onCreate so a failure in the start-up work below is captured too.
     */
    private fun installDiagnostics() {
        Diag.mirror = { level, tag, line, error ->
            when (level) {
                DiagLevel.DEBUG -> Log.d(tag, line)
                DiagLevel.INFO -> Log.i(tag, line)
                DiagLevel.WARN -> Log.w(tag, line, error)
                DiagLevel.ERROR -> Log.e(tag, line, error)
            }
        }
        Diag.install(DiagFileSink(File(filesDir, "diagnostics")))
        DiagCrashHandler.install()
        Diag.i("App", "process start")
    }

    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .build()
}
