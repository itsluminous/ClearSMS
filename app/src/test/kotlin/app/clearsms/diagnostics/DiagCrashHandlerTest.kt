package app.clearsms.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DiagCrashHandlerTest {
    @Test
    fun `records the stack trace into the buffer and still delegates to the previous handler`() {
        var delegated: Pair<Thread, Throwable>? = null
        val previous = Thread.UncaughtExceptionHandler { t, e -> delegated = t to e }
        var flushed = false
        val handler = DiagCrashHandler(previous, flush = { flushed = true })
        val crash = IllegalStateException("otp 123456")

        handler.uncaughtException(Thread.currentThread(), crash)

        val recorded = Diag.buffer.snapshot().last { "uncaught exception" in it.text }
        assertThat(recorded.text).contains("E Crash uncaught exception")
        assertThat(recorded.text).contains("! java.lang.IllegalStateException")
        assertThat(recorded.text).contains("at app.clearsms.diagnostics.DiagCrashHandlerTest")
        assertThat(recorded.text).doesNotContain("123456")
        assertThat(flushed).isTrue()
        assertThat(delegated).isEqualTo(Thread.currentThread() to crash)
    }

    @Test
    fun `a failure inside the recorder never stops the crash reaching the previous handler`() {
        var delegated = false
        val handler = DiagCrashHandler({ _, _ -> delegated = true }, flush = { error("flush broke") })
        handler.uncaughtException(Thread.currentThread(), RuntimeException("boom"))
        assertThat(delegated).isTrue()
    }

    @Test
    fun `install chains to the current default handler exactly once`() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        try {
            var reached = false
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> reached = true }
            DiagCrashHandler.install()
            DiagCrashHandler.install()
            val installed = Thread.getDefaultUncaughtExceptionHandler()
            assertThat(installed).isInstanceOf(DiagCrashHandler::class.java)
            installed!!.uncaughtException(Thread.currentThread(), RuntimeException("x"))
            assertThat(reached).isTrue()
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }
}
