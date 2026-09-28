package app.clearsms.mms

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.AttachmentDao
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.DeliveryStatus
import app.clearsms.data.db.MessageDao
import app.clearsms.diagnostics.Diag
import app.clearsms.receiver.MmsDownloadReceiver
import app.clearsms.receiver.MmsDownloadReport
import app.clearsms.receiver.MmsSendReport
import app.clearsms.receiver.MmsSentReceiver
import app.clearsms.sms.SimInfo
import app.clearsms.sms.SubscriptionSource
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

private class DiagFakeMmsGateway : MmsGateway {
    var throwOnSend: Boolean = false
    var sends = 0
    var lastSentIntent: PendingIntent? = null

    override fun sendMultimediaMessage(
        subscriptionId: Int?,
        pduFile: File,
        sentIntent: PendingIntent,
    ) {
        if (throwOnSend) throw IllegalStateException("radio unavailable for +15551234567")
        sends++
        lastSentIntent = sentIntent
    }
}

private class DiagFakeSubscriptionSource : SubscriptionSource {
    override fun activeSims(): List<SimInfo> =
        listOf(
            SimInfo(subscriptionId = 3, slotIndex = 0, displayName = "Airtel"),
            SimInfo(subscriptionId = 7, slotIndex = 1, displayName = "Jio"),
        )

    override fun defaultSmsSubscriptionId(): Int? = 3

    // Subscription 3 (slot 1) carries mobile data; a send on 7 is off the data SIM.
    var dataSub: Int? = 3

    override fun defaultDataSubscriptionId(): Int? = dataSub
}

/**
 * What the diagnostic report learns about an MMS - and what it must never
 * learn. Issue #51: a send that "fails instantly" produced a report with
 * nothing about MMS in it. Now the hand-over logs the payload shape
 * (parts, byte sizes, MIME types), the radio conditions and the slot; the
 * sent-receiver logs the RAW platform result code beside the mapped
 * reason plus the HTTP status extra; a synchronous throw logs the class
 * chain. The recipient, the text, the attachment's file name and the
 * exception MESSAGE (which may echo any of them) never appear.
 */
@RunWith(RobolectricTestRunner::class)
class MmsDiagnosticsTest {
    private lateinit var context: Context
    private lateinit var db: ClearSmsDatabase
    private lateinit var messageDao: MessageDao
    private lateinit var attachmentDao: AttachmentDao
    private lateinit var gateway: DiagFakeMmsGateway
    private lateinit var subscriptions: DiagFakeSubscriptionSource
    private lateinit var stager: OutgoingAttachmentStager
    private lateinit var sender: MmsSender
    private var logStart = 0L

    private val recipient = "+15551234567"
    private val fileName = "holiday-with-priya.jpg"
    private val text = "look at this photo of us"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        messageDao = db.messageDao()
        attachmentDao = db.attachmentDao()
        gateway = DiagFakeMmsGateway()
        subscriptions = DiagFakeSubscriptionSource()
        stager = OutgoingAttachmentStager(context)
        sender =
            MmsSender(
                context,
                messageDao,
                attachmentDao,
                AttachmentStore(context),
                stager,
                gateway,
                MmsSendConditionsProbe(context, subscriptions),
                Dispatchers.IO,
            )
        // Only entries recorded by THIS test are inspected: the buffer is a
        // process-wide singleton shared with every other test in the JVM.
        logStart = System.currentTimeMillis()
    }

    @After
    fun tearDown() {
        db.close()
        File(context.filesDir, "mms").deleteRecursively()
    }

    private fun log(): String = Diag.buffer.snapshot(sinceMs = logStart).joinToString("\n") { it.text }

    private fun staged(bytes: ByteArray = ByteArray(1_234) { it.toByte() }): StagedAttachment {
        val source = File(context.cacheDir, fileName)
        source.writeBytes(bytes)
        return (stager.stage(Uri.fromFile(source)) as StagingResult.Staged).attachment
    }

    private fun assertNothingPersonal(text: String) {
        assertWithMessage("recipient leaked").that(text).doesNotContain(recipient)
        assertWithMessage("recipient digits leaked").that(text).doesNotContain("5551234567")
        assertWithMessage("file name leaked").that(text).doesNotContain(fileName)
        assertWithMessage("file name stem leaked").that(text).doesNotContain("priya")
        assertWithMessage("message text leaked").that(text).doesNotContain(this.text)
        assertWithMessage("message text leaked").that(text).doesNotContain("photo of us")
        // The SIMs are named after carriers in the fake; only slots may appear.
        assertWithMessage("carrier name leaked").that(text).doesNotContain("Airtel")
        assertWithMessage("carrier name leaked").that(text).doesNotContain("Jio")
        assertWithMessage("subscription id leaked").that(text).doesNotContainMatch("subscription(Id)?=\\d")
    }

    @Test
    fun `a send logs the attachment size and type, the part count, the slot and the radio state - never the recipient or name`() =
        runBlocking {
            val attachment = staged()

            val id = sender.send(recipient, text, listOf(attachment), subscriptionId = 7)

            val log = log()
            val handover = log.lines().single { "mms handover" in it }
            assertThat(handover).contains("I MmsSender mms handover message=$id parts=1")
            assertThat(handover).contains("attachmentBytes=${attachment.sizeBytes}")
            assertThat(handover).containsMatch("pduBytes=[1-9][0-9]*")
            assertThat(handover).contains("resend=false")
            assertThat(handover).contains("defaultSubscription=false")
            // Subscription 7 sits in the second slot; the id itself is not logged.
            assertThat(handover).contains("slot=2")
            assertThat(handover).doesNotContain("subscription=7")
            // Mobile data rides on subscription 3 (slot 1): this MMS went out
            // on the OTHER SIM - the one line that says so.
            assertThat(handover).contains("slot=2 dataSlot=1 onDataSim=NO")
            // Radio facts as YES/NO/UNKNOWN labels, whatever Robolectric answers.
            assertThat(handover).containsMatch("network=(YES|NO|UNKNOWN)")
            assertThat(handover).containsMatch("cellular=(YES|NO|UNKNOWN)")
            assertThat(handover).containsMatch("mobileData=(YES|NO|UNKNOWN)")
            val attachmentLine = log.lines().single { "mms attachment " in it && "message=$id" in it }
            assertThat(attachmentLine).contains("index=0 mime=image/jpeg bytes=${attachment.sizeBytes}")
            // The staging step logged the payload too (the non-JPEG bytes
            // are not compressible, so the size is the source size).
            assertThat(log).contains("attachment staged mime=image/jpeg bytes=${attachment.sizeBytes}")
            assertNothingPersonal(log)
        }

    @Test
    fun `a synchronous hand-over failure logs the exception class - not its message - and records DISPATCH_FAILED`() =
        runBlocking {
            gateway.throwOnSend = true

            val id = sender.send(recipient, text, listOf(staged()))

            val log = log()
            assertThat(log).contains("E MmsSender mms handover failed message=$id parts=1 resend=false")
            assertThat(log).contains("! java.lang.IllegalStateException")
            // The exception message carried the recipient; only the class survives.
            assertThat(log).doesNotContain("radio unavailable")
            assertNothingPersonal(log)
            assertThat(messageDao.getById(id)?.deliveryStatus).isEqualTo(DeliveryStatus.FAILED)
            assertThat(messageDao.getById(id)?.sendFailureReason).isEqualTo("DISPATCH_FAILED")
        }

    @Test
    fun `a resend logs as such and warns when attachment files have gone missing`() =
        runBlocking {
            val id = sender.send(recipient, text, listOf(staged()), subscriptionId = 3)
            messageDao.setDeliveryStatus(id, DeliveryStatus.FAILED)
            // Simulate the user clearing storage between failure and retry.
            File(File(context.filesDir, "mms"), id.toString()).deleteRecursively()

            sender.resend(id)

            val log = log()
            assertThat(log).contains("W MmsSender resend missing attachment files message=$id attachments=1 present=0")
            assertThat(log.lines().last { "mms handover" in it }).contains("parts=0")
            assertThat(log.lines().last { "mms handover" in it }).contains("resend=true defaultSubscription=false slot=1")
            // Subscription 3 IS the data SIM.
            assertThat(log.lines().last { "mms handover" in it }).contains("slot=1 dataSlot=1 onDataSim=YES")
            assertNothingPersonal(log)
        }

    @Test
    fun `an unknown data subscription logs dataSlot=0 and onDataSim=UNKNOWN - never a guess`() =
        runBlocking {
            subscriptions.dataSub = null

            val id = sender.send(recipient, text, listOf(staged()), subscriptionId = 7)

            val handover = log().lines().single { "mms handover" in it && "message=$id" in it }
            assertThat(handover).contains("slot=2 dataSlot=0 onDataSim=UNKNOWN")
            assertNothingPersonal(handover)
        }

    @Test
    fun `a system-default send is judged by the default SMS subscription - what the platform will actually use`() =
        runBlocking {
            // Default SMS subscription is 3 in the fake, and 3 is the data SIM.
            val id = sender.send(recipient, text, listOf(staged()), subscriptionId = null)

            val handover = log().lines().single { "mms handover" in it && "message=$id" in it }
            assertThat(handover).contains("defaultSubscription=true slot=0 dataSlot=1 onDataSim=YES")
            assertNothingPersonal(handover)
        }

    @Test
    fun `the sent intent carries the subscription so the failure report can say whether it was the data SIM`() =
        runBlocking {
            val id = sender.send(recipient, text, listOf(staged()), subscriptionId = 7)

            val sentIntent = shadowOf(gateway.lastSentIntent!!).savedIntent
            assertThat(sentIntent.getLongExtra(MmsSentReceiver.EXTRA_MESSAGE_ID, -1L)).isEqualTo(id)
            assertThat(sentIntent.getIntExtra(MmsSentReceiver.EXTRA_SUBSCRIPTION_ID, 0)).isEqualTo(7)

            val code = SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS
            MmsSendReport.of(sentIntent, code, SendFailureReason.fromMmsResultCode(code), subscriptions).log(id)

            val line = log().lines().single { "mms send failed" in it }
            assertThat(
                line,
            ).contains("message=$id result=$code reason=NO_MMS_NETWORK httpStatusPresent=false httpStatus=0 slot=2 onDataSim=NO")
            assertNothingPersonal(line)
        }

    @Test
    fun `a failure report without a subscription source reads slot and data SIM as unknown`() {
        val intent =
            Intent(MmsSentReceiver.ACTION_MMS_SENT)
                .putExtra(MmsSentReceiver.EXTRA_DESTINATION, recipient)
                .putExtra(MmsSentReceiver.EXTRA_SUBSCRIPTION_ID, 7)
        val code = SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS

        MmsSendReport.of(intent, code, SendFailureReason.fromMmsResultCode(code)).log(21L)

        val line = log().lines().single { "mms send failed" in it }
        assertThat(line).contains("slot=0 onDataSim=UNKNOWN")
        assertNothingPersonal(line)
    }

    @Test
    fun `the sent-receiver report logs the RAW result code, the mapped reason and the HTTP status`() {
        val intent =
            Intent(MmsSentReceiver.ACTION_MMS_SENT)
                .putExtra(MmsSentReceiver.EXTRA_MESSAGE_ID, 42L)
                .putExtra(MmsSentReceiver.EXTRA_DESTINATION, recipient)
                .putExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, 503)
        val code = SmsManager.MMS_ERROR_HTTP_FAILURE

        MmsSendReport.of(intent, code, SendFailureReason.fromMmsResultCode(code)).log(42L)

        val line = log().lines().single { "mms send failed" in it }
        assertThat(
            line,
        ).contains("W MmsSendReport mms send failed message=42 result=4 reason=HTTP_FAILURE httpStatusPresent=true httpStatus=503")
        assertNothingPersonal(line)
    }

    @Test
    fun `an unknown code is logged raw so the report still says what the platform said`() {
        val intent = Intent(MmsSentReceiver.ACTION_MMS_SENT).putExtra(MmsSentReceiver.EXTRA_DESTINATION, recipient)

        MmsSendReport.of(intent, 77, SendFailureReason.fromMmsResultCode(77)).log(9L)

        val line = log().lines().single { "mms send failed" in it }
        assertThat(line).contains("message=9 result=77 reason=UNKNOWN httpStatusPresent=false httpStatus=0")
        assertNothingPersonal(line)
    }

    @Test
    fun `a successful send logs RESULT_OK and the send-conf size, never the PDU`() {
        val pdu = byteArrayOf(0x8c.toByte(), 0x81.toByte(), 0x98.toByte(), 'a'.code.toByte(), 'b'.code.toByte())
        val intent =
            Intent(MmsSentReceiver.ACTION_MMS_SENT)
                .putExtra(MmsSentReceiver.EXTRA_DESTINATION, recipient)
                .putExtra(SmsManager.EXTRA_MMS_DATA, pdu)

        MmsSendReport.of(intent, Activity.RESULT_OK, null).log(5L)

        val line = log().lines().single { "mms sent" in it }
        assertThat(line).contains("I MmsSendReport mms sent message=5 result=-1 sendConf=true sendConfBytes=5")
        assertThat(line).doesNotContain("ab")
        assertNothingPersonal(line)
    }

    @Test
    fun `the download receiver logs the raw code, the attempt, start-failure and HTTP status`() {
        val failed =
            MmsDownloadReceiver
                .intent(context, 11L, attempt = 1)
                .putExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, 404)
        MmsDownloadReport.of(failed, SmsManager.MMS_ERROR_HTTP_FAILURE, attempt = 1).log(11L)

        val startFailed = MmsDownloadReceiver.intent(context, 12L, attempt = 0).putExtra(MmsDownloadReceiver.EXTRA_START_FAILED, true)
        MmsDownloadReport.of(startFailed, Activity.RESULT_OK, attempt = 0).log(12L)

        MmsDownloadReport.of(MmsDownloadReceiver.intent(context, 13L, attempt = 0), Activity.RESULT_OK, attempt = 0).log(13L)

        val log = log()
        assertThat(
            log,
        ).contains(
            "W MmsDownload mms download failed message=11 result=4 attempt=1 startFailed=false httpStatusPresent=true httpStatus=404",
        )
        // RESULT_OK with the start-failed marker is still a failure.
        assertThat(
            log,
        ).contains("W MmsDownload mms download failed message=12 result=-1 attempt=0 startFailed=true httpStatusPresent=false httpStatus=0")
        assertThat(log).contains("I MmsDownload mms downloaded message=13 result=-1 attempt=0")
    }
}
