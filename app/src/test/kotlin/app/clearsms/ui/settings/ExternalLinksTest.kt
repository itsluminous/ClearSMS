package app.clearsms.ui.settings

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.clearsms.BuildConfig
import app.clearsms.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The Source code and Donate rows delegate their URLs (kept in string
 * resources, never inline in code) to other apps via ACTION_VIEW - no
 * INTERNET permission involved - and must not crash when nothing on the
 * device can handle the link.
 */
@RunWith(RobolectricTestRunner::class)
class ExternalLinksTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `source code row builds a browser view intent from the string resource`() {
        val intent = ExternalLinks.intent(context.getString(R.string.url_source_code))
        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.dataString).isEqualTo("https://github.com/itsluminous/ClearSMS")
    }

    @Test
    fun `paypal row builds a view intent from the string resource`() {
        val intent = ExternalLinks.intent(context.getString(R.string.url_donate_paypal))
        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.dataString).isEqualTo("https://paypal.me/prakashait")
    }

    @Test
    fun `upi row builds a upi pay intent from the string resource`() {
        val intent = ExternalLinks.intent(context.getString(R.string.url_donate_upi))
        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.data?.scheme).isEqualTo("upi")
        assertThat(intent.dataString).isEqualTo("upi://pay?pa=electricprakash@axisb&cn=ClearSMS")
    }

    @Test
    fun `version row builds the release notes url from the build versionName`() {
        val url = context.getString(R.string.url_release_notes, BuildConfig.VERSION_NAME)
        assertThat(url)
            .isEqualTo("https://github.com/itsluminous/ClearSMS/releases/tag/v${BuildConfig.VERSION_NAME}")
        // The placeholder must be fully consumed - never a literal %1$s in the link.
        assertThat(url).doesNotContain("%")
        assertThat(BuildConfig.VERSION_NAME).isNotEmpty()
    }

    @Test
    fun `version row resolves to a browser view intent`() {
        val url = context.getString(R.string.url_release_notes, BuildConfig.VERSION_NAME)
        val intent = ExternalLinks.intent(url)
        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.dataString).isEqualTo(url)
    }

    @Test
    fun `open launches the intent and reports success when a handler exists`() {
        // Robolectric resolves everything by default (checkActivities off).
        val url = context.getString(R.string.url_source_code)
        assertThat(ExternalLinks.open(context, url)).isTrue()
        val started = shadowOf(context as Application).nextStartedActivity
        assertThat(started.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(started.dataString).isEqualTo(url)
    }

    @Test
    fun `open does not throw when no app can handle the link`() {
        shadowOf(context as Application).checkActivities(true)
        // No browser and certainly no UPI app registered in this environment.
        assertThat(ExternalLinks.open(context, context.getString(R.string.url_donate_upi))).isFalse()
        assertThat(ExternalLinks.open(context, context.getString(R.string.url_source_code))).isFalse()
    }

    @Test
    fun `a tel link dials rather than views`() {
        // ACTION_DIAL opens the phone app with the number filled in and waits
        // for the user; it needs no CALL_PHONE permission and cannot place the
        // call itself - the right contract for a number from an SMS.
        val intent = ExternalLinks.intent("tel:9871112222")

        assertThat(intent.action).isEqualTo(Intent.ACTION_DIAL)
        assertThat(intent.data.toString()).isEqualTo("tel:9871112222")
    }

    @Test
    fun `a upi link is handed over as a view intent`() {
        val intent = ExternalLinks.intent("upi://pay?pa=someone@examplebank&am=50")

        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.data?.scheme).isEqualTo("upi")
    }

    @Test
    fun `a web link stays a view intent`() {
        val intent = ExternalLinks.intent("https://porter.in/rd/abc")

        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
    }
}
