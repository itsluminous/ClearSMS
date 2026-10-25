package app.clearsms.mms

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.service.carrier.CarrierMessagingService
import android.telephony.SmsManager
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import app.clearsms.diagnostics.Diag
import app.clearsms.receiver.MmsDownloadReceiver
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * The production downloader against Robolectric's platform (issue #135):
 * the write grant goes to the packages the SHARED resolver names - a
 * carrier messaging service first - then to the AOSP fallbacks, each
 * attempt is logged by package name, the start line counts them, and the
 * counts plus the granted names ride the result intent so the receiver's
 * failure line can repeat them. The retrieve rides the SmsManager for the
 * SIM the notification arrived on, and the start line carries the SHAPE
 * of the content location - host, length, query, non-ASCII - while the
 * URL itself (a per-message token in its path or query) never reaches
 * the log.
 */
@RunWith(RobolectricTestRunner::class)
class SystemMmsDownloaderTest {
    private lateinit var context: Context
    private lateinit var store: AttachmentStore
    private var logStart = 0L

    private val location = "http://mmsc.example.net/mms/wapenc?token=SECRET-TOKEN-4242"

    private fun defaultManager() = shadowOf(context.getSystemService(SmsManager::class.java))

    @Before
    fun setUp() {
        runCatching {
            FileProvider::class.java
                .getDeclaredField("sCache")
                .apply { isAccessible = true }
                .get(null)
                .let { (it as MutableMap<*, *>).clear() }
        }
        context = ApplicationProvider.getApplicationContext()
        store = AttachmentStore(context)
        logStart = System.currentTimeMillis()
    }

    @After
    fun tearDown() {
        File(context.filesDir, "mms").deleteRecursively()
    }

    private fun log(): String = Diag.buffer.snapshot(sinceMs = logStart).joinToString("\n") { it.text }

    private fun downloader() = SystemMmsDownloader(context, store, PduGrantTargets(context))

    private fun installCarrierService(packageName: String) {
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(CarrierMessagingService.SERVICE_INTERFACE),
            ResolveInfo().apply {
                serviceInfo =
                    ServiceInfo().apply {
                        this.packageName = packageName
                        name = "$packageName.Mms"
                    }
            },
        )
    }

    @Test
    fun `the write grant reaches the resolved carrier service before the AOSP fallbacks, and each is named`() {
        installCarrierService("com.verizon.messaging.service")

        downloader().download(messageId = 77L, contentLocation = location, attempt = 0, subscriptionId = null)

        val grants = log().lines().filter { "mms pdu grant" in it }
        assertThat(grants.map { it.substringAfter("package=").substringBefore(' ') })
            .containsExactly("com.verizon.messaging.service", "com.android.phone", "com.android.mms.service")
            .inOrder()
        grants.forEach { line ->
            assertWithMessage(line).that(line).contains("message=77")
            assertWithMessage(line).that(line).containsMatch("resolved=(true|false) granted=(true|false)")
        }
        assertThat(grants.single { "com.verizon.messaging.service" in it }).contains("resolved=true")
        val started = log().lines().single { "mms download started" in it }
        assertThat(started).containsMatch("message=77 attempt=0 grantsAttempted=3 grantsLanded=\\d")
        assertThat(started).contains("defaultSubscription=true")
    }

    @Test
    fun `the platform gets the staging URI and a result intent carrying what the grant reached`() {
        installCarrierService("com.verizon.messaging.service")

        downloader().download(messageId = 78L, contentLocation = location, attempt = 1, subscriptionId = null)

        val params = shadowOf(context.getSystemService(SmsManager::class.java)).lastDownloadedMultimediaMessageParams
        assertThat(params).isNotNull()
        assertThat(params!!.locationUrl).isEqualTo(location)
        assertThat(params.contentUri.scheme).isEqualTo("content")
        assertThat(params.contentUri.authority).isEqualTo(context.packageName + ".fileprovider")
        assertThat(store.stagingFile(78L).exists()).isTrue()
        val result = shadowOf(params.downloadedIntent).savedIntent
        assertThat(result.getLongExtra(MmsDownloadReceiver.EXTRA_MESSAGE_ID, -1L)).isEqualTo(78L)
        assertThat(result.getIntExtra(MmsDownloadReceiver.EXTRA_ATTEMPT, -1)).isEqualTo(1)
        assertThat(result.getIntExtra(MmsDownloadReceiver.EXTRA_GRANTS_ATTEMPTED, -1)).isEqualTo(3)
        val landed = result.getIntExtra(MmsDownloadReceiver.EXTRA_GRANTS_LANDED, -1)
        val granted = result.getStringArrayExtra(MmsDownloadReceiver.EXTRA_GRANTED_PACKAGES)!!.toList()
        assertThat(granted).hasSize(landed)
        assertThat(granted).containsNoDuplicates()
        granted.forEach { assertThat(PduGrant.isPackageName(it)).isTrue() }
    }

    @Test
    fun `a missing package is a no-op - the download still starts with the fallbacks alone`() {
        downloader().download(messageId = 79L, contentLocation = location, attempt = 0, subscriptionId = null)

        val grants = log().lines().filter { "mms pdu grant" in it }
        assertThat(grants.map { it.substringAfter("package=").substringBefore(' ') })
            .containsExactly("com.android.phone", "com.android.mms.service")
            .inOrder()
        assertThat(grants.all { "resolved=false" in it }).isTrue()
        assertThat(shadowOf(context.getSystemService(SmsManager::class.java)).lastDownloadedMultimediaMessageParams).isNotNull()
        assertThat(log()).doesNotContain("start failed")
    }

    @Test
    fun `the start line carries the URL's shape - host, length, query, non-ASCII - and nothing else of it`() {
        downloader().download(messageId = 80L, contentLocation = location, attempt = 0, subscriptionId = null)

        val log = log()
        val started = log.lines().single { "mms download started" in it }
        // The MMSC host is carrier infrastructure and is what tells a wrong
        // route from a wrong path; the rest of the URL is reduced to numbers.
        assertThat(started).contains("host=mmsc.example.net")
        assertThat(started).contains("urlLength=${location.length}")
        assertThat(started).contains("urlHasQuery=true")
        assertThat(started).contains("urlNonAscii=false")
        // Never the path, the query or the token - they identify the message.
        assertThat(log).doesNotContain("SECRET-TOKEN")
        assertThat(log).doesNotContain("wapenc")
        assertThat(log).doesNotContain("token=")
        assertThat(log).doesNotContain("80.pdu")
    }

    @Test
    fun `a notified SIM downloads on its own SmsManager, never the default one`() {
        downloader().download(messageId = 81L, contentLocation = location, attempt = 0, subscriptionId = 3)

        // The default manager saw nothing: the call went to the manager
        // created for subscription 3 (the one the send path would use).
        assertThat(defaultManager().lastDownloadedMultimediaMessageParams).isNull()
        assertThat(log().lines().single { "mms download started" in it }).contains("defaultSubscription=false")
        assertThat(log()).doesNotContain("start failed")
        assertThat(store.stagingFile(81L).exists()).isTrue()
    }

    @Test
    fun `the result intent names the subscription the retrieve rode`() {
        downloader().download(messageId = 82L, contentLocation = location, attempt = 0, subscriptionId = null)

        val result = shadowOf(defaultManager().lastDownloadedMultimediaMessageParams!!.downloadedIntent).savedIntent
        assertThat(result.hasExtra(MmsDownloadReceiver.EXTRA_SUBSCRIPTION_ID)).isTrue()
        assertThat(MmsDownloadReceiver.subscriptionIdOf(result)).isNull()
    }
}
