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
    fun `send and download resolve their grant targets through ONE shared resolver - neither guesses`() {
        // Issue #135: the send path resolved its readers (issue #51) while the
        // download path kept a hardcoded pair and drifted. The resolution now
        // lives in exactly one place and BOTH hand-overs go through it.
        val resolver = source("mms/PduGrantTargets.kt")
        assertThat(resolver).contains("CarrierMessagingService.SERVICE_INTERFACE")
        assertThat(resolver).contains("getPackagesForUid(Process.PHONE_UID)")
        val gateway = source("mms/MmsGateway.kt")
        val downloader = source("mms/MmsDownloader.kt")
        listOf("mms/MmsGateway.kt" to gateway, "mms/MmsDownloader.kt" to downloader).forEach { (path, text) ->
            assertWithMessage(path).that(text).contains("PduGrant(grantTargets::resolve)")
            assertWithMessage(path).that(text).contains(".grantAll()")
            // Neither path resolves on its own, and neither carries a hardcoded list.
            assertWithMessage(path).that(text).doesNotContain("CarrierMessagingService")
            assertWithMessage(path).that(text).doesNotContain("PHONE_UID")
            assertWithMessage(path).that(text).doesNotContain("""listOf("com.android.phone", "com.android.mms.service")""")
            assertWithMessage(path).that(text).doesNotContain("""for (pkg in listOf(""")
        }
        // The two AOSP names survive in exactly one place, as the fallback.
        assertThat(source("mms/PduHandover.kt")).contains("""listOf("com.android.phone", "com.android.mms.service")""")
        // The download grant is a WRITE: the platform fills the staging slot.
        assertThat(downloader).contains("Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION")
        // The downloader names each grant the same way the gateway does.
        assertThat(downloader).contains(""""mms pdu grant"""")
        assertThat(downloader).contains("""packageName(outcome.packageName)""")
        assertThat(downloader).contains("""flag("granted", outcome.granted)""")
    }

    @Test
    fun `send and download pick their SmsManager through ONE shared helper - the download never takes the default blindly`() {
        // Issue #135, second drift: the send path chose the SmsManager for
        // its subscription while the download path called the default one
        // whatever SIM the notification arrived on. Both now go through
        // smsManagerFor (MmsSizeBudget.kt), the single per-API-level rule.
        val helper = source("mms/MmsSizeBudget.kt")
        assertThat(helper).contains("internal fun smsManagerFor(")
        assertThat(helper).contains("default.createForSubscriptionId(subscriptionId)")
        assertThat(helper).contains("SmsManager.getSmsManagerForSubscriptionId(subscriptionId)")
        val gateway = source("mms/MmsGateway.kt")
        val downloader = source("mms/MmsDownloader.kt")
        assertThat(gateway).contains("smsManagerFor(context, subscriptionId)")
        assertThat(downloader).contains("smsManagerFor(context, subscriptionId).downloadMultimediaMessage(")
        listOf("mms/MmsGateway.kt" to gateway, "mms/MmsDownloader.kt" to downloader).forEach { (path, text) ->
            // Neither path builds its own manager or reaches for the default.
            assertWithMessage(path).that(text).doesNotContain("getSystemService(SmsManager::class.java)")
            assertWithMessage(path).that(text).doesNotContain("SmsManager.getDefault()")
            assertWithMessage(path).that(text).doesNotContain("createForSubscriptionId")
        }
        // The downloader's contract carries the subscription, and every
        // caller threads it: the notification's SIM, the retry's recorded SIM.
        assertThat(
            downloader,
        ).containsMatch("""fun download\(\s*messageId: Long,\s*contentLocation: String,\s*attempt: Int,\s*subscriptionId: Int\?,\s*\)""")
        val inbound = source("mms/MmsInbound.kt")
        assertThat(
            inbound,
        ).contains("downloader.download(entity.id, notification.contentLocation, attempt = 0, subscriptionId = subscriptionId)")
        assertThat(inbound).contains("downloader.download(messageId, location!!, attempt = attempt + 1, subscriptionId = subscriptionId)")
        assertThat(
            inbound,
        ).contains("downloader.download(messageId, location, attempt = MAX_ATTEMPTS - 1, subscriptionId = row.subscriptionId)")
        assertThat(inbound).contains("subscriptionId = subscriptionId,")
        // The WAP push receiver reads the SIM the way the SMS receiver does.
        val wap = source("receiver/MmsWapPushReceiver.kt")
        assertThat(wap).contains("SmsReceiver.extractSubscriptionId { key, def -> intent.getIntExtra(key, def) }")
        assertThat(wap).contains("mmsInbound.onNotification(pdu, subscriptionId = subscriptionId)")
        // The result intent says which SIM it rode, the send path's convention.
        assertThat(downloader).contains("MmsDownloadReceiver.EXTRA_SUBSCRIPTION_ID")
        assertThat(downloader).contains("subscriptionId ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID")
    }

    @Test
    fun `the download start line carries the URL's shape and never the URL`() {
        // Issue #135: an MMSC that answers 404 within milliseconds of the
        // notification means the host resolved and the path did not. The
        // next report must say whether the URL we handed over was shaped
        // like a URL - without carrying it (its path/query is the token).
        val downloader = source("mms/MmsDownloader.kt")
        assertThat(downloader).contains("val shape = MmsUrlShape.of(contentLocation)")
        assertThat(downloader).contains("""host(shape.host)""")
        assertThat(downloader).contains("""count("urlLength", shape.length)""")
        assertThat(downloader).contains("""flag("urlHasQuery", shape.hasQuery)""")
        assertThat(downloader).contains("""flag("urlNonAscii", shape.nonAscii)""")
        assertThat(downloader).contains("""flag("defaultSubscription", subscriptionId == null)""")
        // The URL itself never reaches a Diag call, in any file.
        listOf(downloader, source("mms/MmsInbound.kt"), source("receiver/MmsDownloadReceiver.kt")).forEach {
            assertThat(it).doesNotContainMatch("""Diag\.[diwe]\([^)]*contentLocation[^!]""")
        }
        // The host travels through its own validated factory - never a free string.
        assertThat(source("diagnostics/DiagField.kt")).contains("fun host(value: String?): DiagField")
    }

    @Test
    fun `the gateway logs grants, staged file and carrier limit by name and count`() {
        val gateway = source("mms/MmsGateway.kt")
        assertThat(gateway).contains("PduGrant(")
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
    fun `the download receiver gets the same treatment - raw code, radio conditions and what the grant reached`() {
        val receiver = source("receiver/MmsDownloadReceiver.kt")
        assertThat(receiver).contains("""code("result", resultCode)""")
        assertThat(receiver).contains("""flag("startFailed", startFailed)""")
        assertThat(receiver).contains("SmsManager.EXTRA_MMS_HTTP_STATUS")
        // Issue #135: the failure line carries the same radio conditions the
        // send path records, probed through the SAME probe...
        // ...on the SIM the retrieve rode (forwarded on the result intent),
        // so `slot` names the notified SIM instead of 0 for "unknown".
        assertThat(receiver).contains("val subscriptionId = subscriptionIdOf(intent)")
        assertThat(receiver).contains("MmsDownloadReport.of(intent, resultCode, attempt, conditions.probe(subscriptionId))")
        assertThat(receiver).doesNotContain("conditions.probe(subscriptionId = null)")
        assertThat(receiver).contains("lateinit var conditions: MmsRadioConditionsProbe")
        assertThat(receiver).contains("""label("network", TriState.of(conditions?.networkConnected))""")
        assertThat(receiver).contains("""label("cellular", TriState.of(conditions?.cellular))""")
        assertThat(receiver).contains("""label("mobileData", TriState.of(conditions?.mobileDataEnabled))""")
        assertThat(receiver).contains("""count("slot", conditions?.slot ?: 0)""")
        assertThat(receiver).contains("""count("dataSlot", conditions?.dataSlot ?: 0)""")
        assertThat(receiver).contains("""label("onDataSim", TriState.of(conditions?.onDataSim))""")
        // ...and what the write grant reached: counts plus the names, each
        // through the package-name factory (never a free string).
        assertThat(receiver).contains("""count("grantsAttempted", grantsAttempted)""")
        assertThat(receiver).contains("""count("grantsLanded", grantsLanded)""")
        assertThat(receiver).contains("""*grantedPackages.map { packageName(it) }.toTypedArray()""")
        assertThat(source("mms/MmsDownloader.kt")).contains("MmsDownloadReceiver.EXTRA_GRANTED_PACKAGES")
        assertThat(source("mms/MmsDownloader.kt")).contains("MmsDownloadReceiver.EXTRA_GRANTS_LANDED")
        // The send path's probe is the one class, not a copy.
        assertThat(source("mms/MmsSender.kt")).contains("private val conditions: MmsRadioConditionsProbe")
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
