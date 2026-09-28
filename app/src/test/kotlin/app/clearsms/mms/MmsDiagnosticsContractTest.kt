package app.clearsms.mms

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Source contract: the MMS send and receive paths log through [app.clearsms.diagnostics.Diag],
 * so a shared diagnostic report can never again say nothing about an MMS
 * failure (issue #51). Behaviour is covered by MmsDiagnosticsTest; this
 * pins the SHAPE of the code so a refactor cannot quietly regress to
 * `android.util.Log` (which never reaches the report) or drop the raw code.
 */
class MmsDiagnosticsContractTest {
    private fun source(path: String): String = File("src/main/kotlin/app/clearsms", path).readText()

    private val mmsPath =
        listOf(
            "mms/MmsSender.kt",
            "mms/MmsInbound.kt",
            "mms/MmsDownloader.kt",
            "mms/OutgoingAttachmentStager.kt",
            "receiver/MmsSentReceiver.kt",
            "receiver/MmsDownloadReceiver.kt",
            "receiver/MmsWapPushReceiver.kt",
        )

    @Test
    fun `no MMS file touches android util Log - every entry goes through Diag`() {
        mmsPath.forEach { path ->
            val text = source(path)
            assertWithMessage(path).that(text).doesNotContain("android.util.Log")
            assertWithMessage(path).that(text).doesNotContainMatch("""\bLog\.[vdiwe]\(""")
            assertWithMessage(path).that(text).contains("Diag.")
        }
    }

    @Test
    fun `the sent-receiver logs the RAW platform result code beside the mapped reason`() {
        val receiver = source("receiver/MmsSentReceiver.kt")
        assertThat(receiver).contains("""code("result", resultCode)""")
        assertThat(receiver).contains("""label("reason", reason)""")
        assertThat(receiver).contains("SmsManager.EXTRA_MMS_HTTP_STATUS")
        assertThat(receiver).contains("SmsManager.EXTRA_MMS_DATA")
        // The report is logged in onReceive, before the coroutine hop, and
        // says whether the sending SIM was the phone's mobile-data SIM.
        assertThat(receiver).contains("MmsSendReport.of(intent, resultCode, failureReason, subscriptionSource).log(messageId)")
        assertThat(receiver).contains("""count("slot", slot ?: 0)""")
        assertThat(receiver).contains("""label("onDataSim", TriState.of(onDataSim))""")
    }

    @Test
    fun `the download receiver gets the same treatment`() {
        val receiver = source("receiver/MmsDownloadReceiver.kt")
        assertThat(receiver).contains("""code("result", resultCode)""")
        assertThat(receiver).contains("""flag("startFailed", startFailed)""")
        assertThat(receiver).contains("SmsManager.EXTRA_MMS_HTTP_STATUS")
    }

    @Test
    fun `the sender logs payload shape and radio conditions at hand-over, and the class chain on a throw`() {
        val sender = source("mms/MmsSender.kt")
        assertThat(sender).contains(""""mms handover"""")
        assertThat(sender).contains("""count("parts", parts.size)""")
        assertThat(sender).contains("""count("attachmentBytes"""")
        assertThat(sender).contains("""count("pduBytes", pduBytes)""")
        assertThat(sender).contains("""mime(part.mimeType)""")
        assertThat(sender).contains("""count("slot", radio.slot ?: 0)""")
        // The data-SIM datum: which slot carries mobile data, and whether
        // the sending SIM is it. Existing field names are untouched.
        assertThat(sender).contains("""count("dataSlot", radio.dataSlot ?: 0)""")
        assertThat(sender).contains("""label("onDataSim", TriState.of(radio.onDataSim))""")
        assertThat(sender).contains("""flag("defaultSubscription", subscriptionId == null)""")
        // The sent intent tells the receiver which subscription sent.
        assertThat(sender).contains("MmsSentReceiver.EXTRA_SUBSCRIPTION_ID")
        assertThat(sender).contains(""""mms handover failed", e""")
        assertThat(sender).contains("SendFailureReason.DISPATCH_FAILED.name")
        // The destination reaches the encoder and the PendingIntent only.
        assertThat(sender).doesNotContainMatch("""Diag\.[diwe]\([^)]*destination""")
    }

    @Test
    fun `the receive path logs notification, download start, result and parse outcome`() {
        val inbound = source("mms/MmsInbound.kt")
        assertThat(inbound).contains(""""mms notification parsed"""")
        assertThat(inbound).contains(""""mms download attempt failed"""")
        assertThat(inbound).contains(""""mms retrieve-conf parsed"""")
        assertThat(inbound).contains(""""staged mms pdu unreadable", e""")
        assertThat(source("mms/MmsDownloader.kt")).contains(""""mms download start failed", e""")
        assertThat(source("receiver/MmsWapPushReceiver.kt")).contains(""""incoming mms notification failed", e""")
        // The carrier's content location is a URL with a per-message token:
        // referenced only as a presence flag.
        assertThat(inbound).doesNotContainMatch("""Diag\.[diwe]\([^)]*contentLocation[^!]""")
    }

    @Test
    fun `the SMS hand-over failure is no longer silent either`() {
        val sms = source("sms/SmsSender.kt")
        assertThat(sms).contains(""""sms handover failed"""")
        assertThat(sms).containsMatch("""Diag\.e\(\s*TAG,\s*"sms handover failed",\s*e,""")
        assertThat(sms).contains("SendFailureReason.DISPATCH_FAILED.name")
        assertThat(sms).doesNotContain("catch (_: Exception)")
    }
}
