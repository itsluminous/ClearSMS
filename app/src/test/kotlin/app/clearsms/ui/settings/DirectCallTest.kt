package app.clearsms.ui.settings

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * [DirectCall] - the one mapping behind every Call button. The pure
 * [DirectCall.intent] truth table, and [DirectCall.place]'s live behaviour:
 * the permission is read at call time, a missing grant ends in the dialer,
 * a non-Activity context gets its own task, and a device with no dialer at
 * all reports false instead of crashing.
 */
@RunWith(RobolectricTestRunner::class)
class DirectCallTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val app get() = shadowOf(context as Application)

    @Test
    fun `truth table - ACTION_CALL only when opted in AND permitted, the dialer in every other case`() {
        val cases =
            listOf(
                Triple(false, false, Intent.ACTION_DIAL),
                Triple(false, true, Intent.ACTION_DIAL),
                Triple(true, false, Intent.ACTION_DIAL),
                Triple(true, true, Intent.ACTION_CALL),
            )
        for ((directCall, permitted, expected) in cases) {
            val intent = DirectCall.intent("9871112222", directCall = directCall, permitted = permitted)
            assertThat(intent.action).isEqualTo(expected)
            // The number is the same tel: URI either way - the setting changes
            // what happens, never who is called.
            assertThat(intent.data.toString()).isEqualTo("tel:9871112222")
        }
        // The dialer branch IS ExternalLinks' tel: mapping, not a copy of it.
        assertThat(DirectCall.intent("1", directCall = false, permitted = true).action)
            .isEqualTo(ExternalLinks.intent("tel:1").action)
    }

    @Test
    fun `the permission constant is CALL_PHONE and is read live from the package manager`() {
        assertThat(DirectCall.PERMISSION).isEqualTo(Manifest.permission.CALL_PHONE)
        assertThat(DirectCall.isPermitted(context)).isFalse()
        app.grantPermissions(Manifest.permission.CALL_PHONE)
        assertThat(DirectCall.isPermitted(context)).isTrue()
        app.denyPermissions(Manifest.permission.CALL_PHONE)
        assertThat(DirectCall.isPermitted(context)).isFalse()
    }

    @Test
    fun `place with direct call ON but the grant revoked opens the dialer - never nothing`() {
        // The user opted in, then removed the permission in system settings.
        app.denyPermissions(Manifest.permission.CALL_PHONE)
        assertThat(DirectCall.place(context, "9871112222", directCall = true)).isTrue()
        val started = app.nextStartedActivity
        assertThat(started.action).isEqualTo(Intent.ACTION_DIAL)
        assertThat(started.data.toString()).isEqualTo("tel:9871112222")
        // A non-Activity context (the notification path) needs its own task.
        assertThat(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
    }

    @Test
    fun `place with direct call ON and the grant held places the call`() {
        app.grantPermissions(Manifest.permission.CALL_PHONE)
        assertThat(DirectCall.place(context, "9871112222", directCall = true)).isTrue()
        val started = app.nextStartedActivity
        assertThat(started.action).isEqualTo(Intent.ACTION_CALL)
        assertThat(started.data.toString()).isEqualTo("tel:9871112222")
    }

    @Test
    fun `place with direct call OFF dials even when the grant is held - opting in is the user's, not the permission's`() {
        app.grantPermissions(Manifest.permission.CALL_PHONE)
        assertThat(DirectCall.place(context, "9871112222", directCall = false)).isTrue()
        assertThat(app.nextStartedActivity.action).isEqualTo(Intent.ACTION_DIAL)
    }

    @Test
    fun `place reports false instead of crashing when no app can take the number`() {
        app.checkActivities(true)
        assertThat(DirectCall.place(context, "9871112222", directCall = false)).isFalse()
    }
}
