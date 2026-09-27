package app.clearsms.diagnostics

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The app's diagnostic logger: the ONLY sink the "Share diagnostic logs"
 * report reads from. Entries go to an in-memory ring ([DiagBuffer], 2 000
 * entries / 256 KiB) and, once [install]ed by the Application, to a small
 * rotating file ([DiagFileSink]) so a report survives a process restart.
 *
 * Privacy is enforced by the API shape, not by discipline: the event is a
 * constant phrase and every value travels as a [DiagField], which has no
 * free-form string factory (see [DiagField] and `DiagnosticLogConventionTest`).
 * Exception messages are dropped at render time - only class names and stack
 * frames are kept.
 *
 * `android.util.Log` is never touched here, so the pure-JVM core is usable
 * from plain unit tests; the Application installs a [mirror] that echoes
 * entries to logcat for developers.
 */
object Diag {
    /** The live buffer; tests read it directly, the report reads a snapshot. */
    val buffer = DiagBuffer()

    /** Optional logcat echo installed by the Application (null in tests). */
    @Volatile
    var mirror: ((level: DiagLevel, tag: String, line: String, error: Throwable?) -> Unit)? = null

    @Volatile
    private var sink: DiagFileSink? = null

    // One writer thread keeps file appends off the caller (often the main
    // thread inside a BroadcastReceiver) and serialises them for the sink.
    private val writer =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "clearsms-diag").apply { isDaemon = true }
        }

    /**
     * Attaches the rotating file: restores its lines into the buffer, then
     * mirrors new ones. Null detaches (tests), leaving the buffer as is.
     */
    fun install(fileSink: DiagFileSink?) {
        if (fileSink == null) {
            flushSync()
            sink = null
            return
        }
        val restored =
            try {
                fileSink.restore()
            } catch (_: Exception) {
                emptyList()
            }
        synchronized(buffer) {
            restored.forEach(buffer::append)
        }
        sink = fileSink
    }

    fun d(
        tag: String,
        event: String,
        vararg fields: DiagField,
    ) = record(DiagLevel.DEBUG, tag, event, fields.asList(), null)

    fun i(
        tag: String,
        event: String,
        vararg fields: DiagField,
    ) = record(DiagLevel.INFO, tag, event, fields.asList(), null)

    fun w(
        tag: String,
        event: String,
        error: Throwable? = null,
        vararg fields: DiagField,
    ) = record(DiagLevel.WARN, tag, event, fields.asList(), error)

    fun e(
        tag: String,
        event: String,
        error: Throwable? = null,
        vararg fields: DiagField,
    ) = record(DiagLevel.ERROR, tag, event, fields.asList(), error)

    /** Records one entry; [nowMs] is injectable for tests. */
    fun record(
        level: DiagLevel,
        tag: String,
        event: String,
        fields: List<DiagField>,
        error: Throwable?,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val line = DiagLine.render(nowMs, level, tag, event, fields, error)
        buffer.append(line)
        mirror?.invoke(level, tag, line.text, error)
        val target = sink ?: return
        try {
            writer.execute {
                try {
                    target.append(line)
                } catch (_: Exception) {
                    // Disk trouble must never take the logger down.
                }
            }
        } catch (_: Exception) {
            // Executor shut down (process exiting) - the in-memory copy stands.
        }
    }

    /** Waits for pending file appends; used by the crash handler before the process dies. */
    fun flushSync(timeoutMs: Long = 2_000) {
        try {
            writer.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            // Best effort: a crash must still reach the default handler.
        }
    }
}

/**
 * Uncaught-exception hook: records the crash into [Diag] (the most valuable
 * thing a bug report can carry), flushes the file, then hands the throwable
 * to whatever handler was installed before - never swallowing it, so the
 * platform still shows its crash dialog and restarts the process normally.
 */
class DiagCrashHandler(
    private val previous: Thread.UncaughtExceptionHandler?,
    private val flush: () -> Unit = { Diag.flushSync() },
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(
        thread: Thread,
        throwable: Throwable,
    ) {
        try {
            Diag.e(TAG, "uncaught exception", throwable, DiagField.flag("mainThread", thread.name == "main"))
            flush()
        } catch (_: Throwable) {
            // Nothing may stand between the crash and the previous handler.
        }
        previous?.uncaughtException(thread, throwable)
    }

    companion object {
        private const val TAG = "Crash"

        /** Installs the handler once, chaining to the current default. */
        fun install() {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current is DiagCrashHandler) return
            Thread.setDefaultUncaughtExceptionHandler(DiagCrashHandler(current))
        }
    }
}
