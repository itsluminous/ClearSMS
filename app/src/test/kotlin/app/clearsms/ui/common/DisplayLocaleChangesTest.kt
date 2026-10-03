package app.clearsms.ui.common

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * [displayLocaleChanges] is what lets a view model that pre-formats labels
 * (inbox / bin / archive rows, conversation bubbles) redo them after a
 * language switch, so it must fire on exactly that: a configuration whose
 * locale differs from the last one seen, delivered to the application.
 */
@RunWith(RobolectricTestRunner::class)
class DisplayLocaleChangesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val application = context as Application

    /**
     * The configuration as the system would have it: each delivery builds on
     * the previous one (a real device updates the application's resources
     * before the callback; Robolectric's delivery leaves them alone).
     */
    private var current = Configuration(context.resources.configuration)

    private fun deliver(configure: Configuration.() -> Unit) {
        current = Configuration(current).apply(configure)
        application.onConfigurationChanged(current)
    }

    @Test
    fun `fires once per language change and not for other configuration changes`() =
        runTest {
            context.displayLocaleChanges().test {
                expectNoEvents()

                deliver { setLocale(Locale("hi")) }
                awaitItem()

                // The same language re-delivered (a rotation re-sends the
                // current configuration) and a font-scale change are not
                // language changes.
                deliver { setLocale(Locale("hi")) }
                deliver { fontScale = 1.3f }
                expectNoEvents()

                deliver { setLocale(Locale.ENGLISH) }
                awaitItem()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `stops listening when the collector is cancelled`() =
        runTest {
            val seen = MutableStateFlow(0)
            val job = launch { context.displayLocaleChanges().collect { seen.value++ } }
            advanceUntilIdle()
            deliver { setLocale(Locale("hi")) }
            advanceUntilIdle()
            assertThat(seen.value).isEqualTo(1)

            job.cancel()
            advanceUntilIdle()
            // Delivered to nobody: a cancelled collector left no callback
            // behind on the application.
            deliver { setLocale(Locale.ENGLISH) }
            advanceUntilIdle()
            assertThat(seen.value).isEqualTo(1)
        }
}
