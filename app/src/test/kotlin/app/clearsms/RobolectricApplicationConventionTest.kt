package app.clearsms

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards against Robolectric booting the real @HiltAndroidApp application.
 *
 * WHY: [ClearSmsApplication.onCreate] builds the full production Hilt graph
 * and launches `UndoManager.onAppStart()` into the never-cancelled
 * @ApplicationScope. Under Robolectric that happens ONCE PER TEST inside the
 * single shared sandbox classloader: each boot leaks an application-scope
 * job, an unclosed Room database (CloseGuard "close not called"), and binds
 * the process-wide DataStore delegates to a soon-deleted per-test dir. The
 * accumulated leaks starved the shared "SDK 35 Main Thread" on slow CI and
 * hung RulesViewModelDetailTest with UncompletedCoroutinesError (v0.10.3).
 *
 * [TestClearSmsApplication] (Robolectric's `Test`-prefix convention) keeps
 * every test on an inert Application. This test fails loudly if that class
 * is renamed, moved, or stops being picked up - e.g. after renaming
 * ClearSmsApplication without renaming its test twin.
 */
@RunWith(RobolectricTestRunner::class)
class RobolectricApplicationConventionTest {
    @Test
    fun `unit tests boot the inert test application, never the Hilt graph`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertWithMessage(
            "Robolectric booted %s. Unit tests must boot TestClearSmsApplication " +
                "(Robolectric resolves Test<ManifestAppName> in the same package); booting the " +
                "real @HiltAndroidApp leaks an app-scope job + an unclosed Room DB per test and " +
                "re-binds process-wide DataStores - the proven cause of the CI-only " +
                "RulesViewModelDetailTest hang. If ClearSmsApplication was renamed, rename its " +
                "test twin to match.",
            app.javaClass.name,
        ).that(app).isInstanceOf(TestClearSmsApplication::class.java)
    }
}
