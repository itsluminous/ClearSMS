package app.clearsms.mms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import app.clearsms.diagnostics.Diag
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
 * The production gateway against Robolectric's platform: the read grant is
 * attempted for the two AOSP fallback packages at least, the staged file
 * is inspected and read back through our own FileProvider before the
 * platform is called, every datum lands in the diagnostic log by package
 * name and byte count - never by file name - and an unreadable staging
 * file is refused before hand-over (issue #51).
 */
@RunWith(RobolectricTestRunner::class)
class FrameworkMmsGatewayTest {
    private lateinit var context: Context
    private lateinit var gateway: FrameworkMmsGateway
    private lateinit var store: AttachmentStore
    private var logStart = 0L

    @Before
    fun setUp() {
        // androidx FileProvider caches its path strategy per authority in a
        // static map; each Robolectric test has a fresh data dir, so a stale
        // entry would make every test after the first fail on our side.
        runCatching {
            FileProvider::class.java
                .getDeclaredField(
                    "sCache",
                ).apply { isAccessible = true }
                .get(null)
                .let { (it as MutableMap<*, *>).clear() }
        }
        context = ApplicationProvider.getApplicationContext()
        gateway = FrameworkMmsGateway(context)
        store = AttachmentStore(context)
        logStart = System.currentTimeMillis()
    }

    @After
    fun tearDown() {
        File(context.filesDir, "mms").deleteRecursively()
    }

    private fun log(): String = Diag.buffer.snapshot(sinceMs = logStart).joinToString("\n") { it.text }

    private fun sentIntent(): PendingIntent =
        PendingIntent.getBroadcast(context, 1, Intent("test.MMS_SENT"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

    @Test
    fun `a staged PDU is granted, inspected, read back through the provider and handed over`() {
        val pdu = store.stagingFile(4_564L)
        pdu.writeBytes(ByteArray(2_048) { it.toByte() })

        gateway.sendMultimediaMessage(4_564L, null, pdu, sentIntent())

        val log = log()
        val grants = log.lines().filter { "mms pdu grant" in it }
        // Robolectric resolves no carrier service and no telephony uid, so
        // the fallbacks alone are attempted - and both are named.
        assertThat(grants.map { it.substringAfter("package=").substringBefore(' ') })
            .containsAtLeast("com.android.phone", "com.android.mms.service")
        grants.forEach { line ->
            assertWithMessage(line).that(line).contains("message=4564")
            assertWithMessage(line).that(line).containsMatch("resolved=(true|false) granted=(true|false)")
        }
        val staged = log.lines().single { "mms pdu staged" in it }
        assertThat(staged).contains("message=4564 exists=true bytes=2048 readable=true readableBytes=2048")
        assertThat(staged).containsMatch("carrierMaxBytes=\\d+ sizeOverride=(true|false) grantsAttempted=\\d+ grantsLanded=\\d+")
        // The file name never appears.
        assertThat(log).doesNotContain("4564.pdu")
        assertThat(log).doesNotContain("staging/")
        // The platform received the FileProvider URI, not a file path.
        val params = shadowOf(context.getSystemService(SmsManager::class.java)).lastSentMultimediaMessageParams
        assertThat(params).isNotNull()
        assertThat(params!!.contentUri.scheme).isEqualTo("content")
        assertThat(params.contentUri.authority).isEqualTo(context.packageName + ".fileprovider")
    }

    @Test
    fun `a missing staging file is refused before the platform is asked, and says so`() {
        val pdu = store.stagingFile(99L)
        pdu.delete()

        val thrown = runCatching { gateway.sendMultimediaMessage(99L, null, pdu, sentIntent()) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(StagedPduUnreadableException::class.java)
        val staged = log().lines().single { "mms pdu staged" in it }
        assertThat(staged).contains("message=99 exists=false bytes=0 readable=false readableBytes=0")
        assertThat(shadowOf(context.getSystemService(SmsManager::class.java)).lastSentMultimediaMessageParams).isNull()
    }

    @Test
    fun `an empty staging file is refused too - the platform would read zero bytes and answer IO_ERROR`() {
        val pdu = store.stagingFile(7L)
        pdu.writeBytes(ByteArray(0))

        val thrown = runCatching { gateway.sendMultimediaMessage(7L, null, pdu, sentIntent()) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(StagedPduUnreadableException::class.java)
        assertThat(log().lines().single { "mms pdu staged" in it }).contains("exists=true bytes=0")
    }
}
