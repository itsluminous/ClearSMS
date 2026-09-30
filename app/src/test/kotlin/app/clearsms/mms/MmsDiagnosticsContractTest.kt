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
            "mms/MmsGateway.kt",
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
        // Issue #51: how long the platform took - the instant-IO-error tell.
        assertThat(receiver).contains("""count("elapsedMs", elapsedMs ?: -1L)""")
        assertThat(receiver).contains("MmsSentReceiver.EXTRA_HANDOVER_ELAPSED_REALTIME_MS")
    }

    @Test
    fun `the gateway resolves the PDU reader instead of guessing, and logs grants, staged file and carrier limit by name and count`() {
        val gateway = source("mms/MmsGateway.kt")
        // Who reads the PDU is asked of the platform; the two AOSP names are fallbacks, not the list.
        assertThat(gateway).contains("CarrierMessagingService.SERVICE_INTERFACE")
        assertThat(gateway).contains("getPackagesForUid(Process.PHONE_UID)")
        assertThat(gateway).contains("PduReadGrant(")
        assertThat(gateway).doesNotContain("""for (pkg in listOf("com.android.phone", "com.android.mms.service"))""")
        assertThat(source("mms/PduHandover.kt")).contains("""listOf("com.android.phone", "com.android.mms.service")""")
        // The next report says which grant landed and whether the file was there and readable.
        assertThat(gateway).contains(""""mms pdu grant"""")
        assertThat(gateway).contains("""packageName(outcome.packageName)""")
        assertThat(gateway).contains("""flag("granted", outcome.granted)""")
        assertThat(gateway).contains(""""mms pdu staged"""")
        assertThat(gateway).contains("""flag("exists", exists)""")
        assertThat(gateway).contains("""count("bytes", lengthBytes)""")
        assertThat(gateway).contains("""flag("readable", readableBytes != null)""")
        assertThat(gateway).contains("""count("carrierMaxBytes", carrierMax ?: 0)""")
        assertThat(gateway).contains("""flag("fitsCarrierMax", MmsSizeBudget.forCarrier(carrierMax).fits(lengthBytes))""")
        // The carrier limit is read in ONE place and never overridden on the hand-over.
        assertThat(source("mms/MmsSizeBudget.kt")).contains("SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE")
        assertThat(gateway).doesNotContain("MMS_CONFIG_MAX_MESSAGE_SIZE")
        assertThat(gateway).doesNotContain("MmsSizeOverride")
        assertThat(gateway).contains("manager.sendMultimediaMessage(context, contentUri, null, null, sentIntent)")
        // We read the URI the way the platform will, and refuse to hand over what we cannot read.
        assertThat(gateway).contains("""openFileDescriptor(uri, "r")""")
        assertThat(gateway).contains("throw StagedPduUnreadableException()")
        // The PDU is never copied: no second file, no world-readable location.
        assertThat(gateway).doesNotContain("copyTo(")
        assertThat(gateway).doesNotContain("MODE_WORLD_READABLE")
        assertThat(gateway).doesNotContain("getExternalFilesDir")
        assertThat(gateway).doesNotContain("Environment.getExternal")
        // The manifest lets the resolver see the carrier messaging service on Android 11+.
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertThat(manifest).contains("<queries>")
        assertThat(manifest).contains("""<action android:name="android.service.carrier.CarrierMessagingService" />""")
    }

    @Test
    fun `the sender checks the staged file before hand-over and refuses an incomplete one`() {
        val sender = source("mms/MmsSender.kt")
        assertThat(sender).contains("StagedPduCheck.of(staged.exists(), staged.length(), pdu.size)")
        assertThat(sender).contains("""flag("stagedExists", staged.exists)""")
        assertThat(sender).contains("""count("stagedBytes", staged.lengthBytes)""")
        assertThat(sender).contains(""""staged pdu incomplete before handover"""")
        assertThat(sender).contains("if (!check.handoverSafe)")
    }

    @Test
    fun `the fit is logged end to end - carrier limit and target at staging, PDU size against the limit at hand-over`() {
        val stager = source("mms/OutgoingAttachmentStager.kt")
        assertThat(stager).contains(""""attachment staged"""")
        assertThat(stager).contains("""count("carrierMaxBytes", budget.carrierMaxBytes ?: 0)""")
        assertThat(stager).contains("""flag("limitKnown", budget.limitKnown)""")
        assertThat(stager).contains("""count("targetBytes", targetBytes)""")
        assertThat(stager).contains(""""attachment refused after compression"""")
        assertThat(stager).contains("""count("achievedBytes", achievedBytes)""")
        val sender = source("mms/MmsSender.kt")
        assertThat(sender).contains("""count("carrierMaxBytes", budget.carrierMaxBytes ?: 0)""")
        assertThat(sender).contains("""count("limitBytes", budget.limitBytes)""")
        assertThat(sender).contains("""flag("fitsCarrierMax", budget.fits(pduBytes.toLong()))""")
        // A PDU over the limit is refused with its own reason, never handed over to fail at once.
        assertThat(sender).contains(""""pdu exceeds carrier limit before handover"""")
        assertThat(sender).contains("SendFailureReason.EXCEEDS_CARRIER_LIMIT.name")
        assertThat(sender).contains("if (!budget.fits(pdu.size.toLong()))")
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
