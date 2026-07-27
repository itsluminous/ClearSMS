package app.clearsms.di

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The application scope hosts ingestion work launched from broadcast
 * receivers. A root `launch` that throws would normally reach the thread's
 * default handler and crash the process (SupervisorJob does not change that);
 * the scope's CoroutineExceptionHandler must swallow-and-log instead, and the
 * scope must stay usable for subsequent messages.
 */
@RunWith(RobolectricTestRunner::class)
class ApplicationScopeCrashSafetyTest {
    @Test
    fun `uncaught exception in a launched job does not kill the scope`() {
        val dispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        val scope = PlatformModule.provideApplicationScope(dispatcher)

        runBlocking {
            scope.launch { throw IllegalStateException("simulated ingestion failure") }.join()

            var ranAfterFailure = false
            scope.launch { ranAfterFailure = true }.join()
            assertThat(ranAfterFailure).isTrue()
        }
    }
}
