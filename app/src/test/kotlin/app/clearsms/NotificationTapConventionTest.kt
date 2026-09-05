package app.clearsms

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the notification-tap regression (GitHub issue #8): tapping a
 * message notification while the app was already running did nothing.
 *
 * The NavController resolves deep links only from the intent it was
 * constructed with (the one `onCreate` saw). Delivering a later tap to a
 * live app therefore needs BOTH halves below - and removing either is
 * invisible in code review, because the cold-start path keeps working:
 *
 * 1. `launchMode="singleTop"` in the manifest, so the tap resumes the
 *    existing instance instead of stacking a second MainActivity;
 * 2. a `MainActivity.onNewIntent` override, which is the only place the
 *    resumed instance ever sees the new intent (it relays it into the
 *    composition via `laterIntents`).
 */
@RunWith(RobolectricTestRunner::class)
class NotificationTapConventionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `MainActivity is declared singleTop in the merged manifest`() {
        val info =
            context.packageManager.getActivityInfo(
                ComponentName(context, MainActivity::class.java),
                0,
            )
        assertThat(info.launchMode).isEqualTo(ActivityInfo.LAUNCH_SINGLE_TOP)
    }

    @Test
    fun `MainActivity overrides onNewIntent to consume later intents`() {
        // getDeclaredMethod throws if MainActivity itself stops declaring the
        // override (an inherited no-op would silently drop notification taps).
        val method = MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
        assertThat(method.declaringClass).isEqualTo(MainActivity::class.java)
    }
}
