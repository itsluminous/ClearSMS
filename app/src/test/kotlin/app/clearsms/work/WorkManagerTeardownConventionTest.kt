package app.clearsms.work

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Guards against test-abandoned WorkManager workers, a suite-wide flake.
 *
 * WHY: a CoroutineWorker still RUNNING when its test ends never completes
 * its result future. Long after the test - once the GC runs - the
 * finalizer (`CallbackToFutureAdapter.Completer.finalize`) completes that
 * future exceptionally, `WorkerWrapper`'s failure path re-enters
 * `Schedulers` against the test's long-closed in-memory Work database, and
 * the resulting `IllegalStateException: The database ':memory:' is not
 * open` is an UNCAUGHT coroutine exception. kotlinx-coroutines-test then
 * fails whichever unrelated `runTest` happens to run next with
 * `UncaughtExceptionsBeforeTest`. This is timing- (GC-) dependent, so it
 * only surfaced on slow 2-vCPU CI runners (run 34743528778, where
 * `ReminderSchedulingGateTest` was the innocent victim of a worker parked
 * by `AutoResortSchedulerTest`).
 *
 * THE RULE: every test file that initializes a test WorkManager must also
 * cancel all work in an `@After` teardown (`workManager.cancelAllWork()
 * .result.get()`), so every worker future completes while that
 * WorkManager's database is still open and nothing is left for the
 * finalizer.
 */
class WorkManagerTeardownConventionTest {
    @Test
    fun `every test initializing WorkManager cancels all work in teardown`() {
        val sourceRoot = File("src/test/kotlin")
        assertWithMessage("test must run from the app module").that(sourceRoot.isDirectory).isTrue()
        val violations = mutableListOf<String>()
        sourceRoot
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = file.readText()
                if (!text.contains("initializeTestWorkManager")) return@forEach
                val hasTeardownCancel = text.contains("@After") && text.contains("cancelAllWork")
                if (!hasTeardownCancel) {
                    violations += file.toRelativeString(sourceRoot)
                }
            }
        assertWithMessage(
            "These test files initialize a test WorkManager but do not cancel " +
                "all work in an @After teardown. An abandoned RUNNING worker's " +
                "future is completed exceptionally by the GC finalizer after the " +
                "Work database is closed, and the uncaught exception fails an " +
                "unrelated later runTest (UncaughtExceptionsBeforeTest). Add:\n" +
                "  @After fun tearDown() { workManager.cancelAllWork().result.get() }",
        ).that(violations).isEmpty()
    }
}
