package app.clearsms.mms

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.AttachmentDao
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.DeliveryStatus
import app.clearsms.data.db.MessageDao
import app.clearsms.sms.SimInfo
import app.clearsms.sms.SubscriptionSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Captures dispatches instead of touching the radio. */
private class FakeMmsGateway : MmsGateway {
    data class Send(
        val messageId: Long,
        val subscriptionId: Int?,
        val pduFile: File,
    )

    val sends = mutableListOf<Send>()
    var throwOnSend: Boolean = false
    var unreadableOnSend: Boolean = false

    override fun sendMultimediaMessage(
        messageId: Long,
        subscriptionId: Int?,
        pduFile: File,
        sentIntent: PendingIntent,
    ) {
        if (throwOnSend) throw IllegalStateException("radio unavailable")
        if (unreadableOnSend) throw StagedPduUnreadableException()
        sends += Send(messageId, subscriptionId, pduFile)
    }
}

/** Two active SIMs so a subscription resolves to a slot number. */
private class TwoSimSubscriptionSource : SubscriptionSource {
    override fun activeSims(): List<SimInfo> =
        listOf(
            SimInfo(subscriptionId = 3, slotIndex = 0, displayName = "A"),
            SimInfo(subscriptionId = 7, slotIndex = 1, displayName = "B"),
        )

    override fun defaultSmsSubscriptionId(): Int? = 3

    override fun defaultDataSubscriptionId(): Int? = null
}

/**
 * The outgoing MMS path: the row and its attachment rows/files are
 * persisted before dispatch (the bubble maps them exactly like received
 * ones), the chosen SIM's subscription flows to the gateway, a
 * synchronous dispatch failure marks the row FAILED, and a resend reuses
 * the SAME row and SIM.
 */
@RunWith(RobolectricTestRunner::class)
class MmsSenderTest {
    private lateinit var context: Context
    private lateinit var db: ClearSmsDatabase
    private lateinit var messageDao: MessageDao
    private lateinit var attachmentDao: AttachmentDao
    private lateinit var gateway: FakeMmsGateway
    private lateinit var carrier: FakeCarrierMmsLimits
    private lateinit var stager: OutgoingAttachmentStager
    private lateinit var sender: MmsSender

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
        gateway = FakeMmsGateway()
        // The reporter's carrier: the AOSP default 300 KiB.
        carrier = FakeCarrierMmsLimits(307_200)
        stager = OutgoingAttachmentStager(context, carrier)
        sender =
            MmsSender(
                context,
                messageDao,
                attachmentDao,
                AttachmentStore(context),
                stager,
                gateway,
                MmsSendConditionsProbe(context, TwoSimSubscriptionSource()),
                carrier,
                Dispatchers.IO,
            )
    }

    @After
    fun tearDown() {
        db.close()
        File(context.filesDir, "mms").deleteRecursively()
    }

    private fun staged(
        name: String = "photo.jpg",
        mime: String = "image/jpeg",
        bytes: ByteArray = byteArrayOf(1, 2, 3),
    ): StagedAttachment {
        val source = File(context.cacheDir, name)
        source.writeBytes(bytes)
        val uri = Uri.fromFile(source)
        return (stager.stage(uri, stager.budgetFor(null)) as StagingResult.Staged).attachment
    }

    /** A large per-pixel-noise JPEG - several MB at 2048 px, so it must be compressed to fit. */
    private fun bigNoisyImage(name: String = "noise.jpg"): File {
        val random = java.util.Random(11)
        val width = 3000
        val height = 2000
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(width * height) { random.nextInt() or 0xFF000000.toInt() }, 0, width, 0, 0, width, height)
        val file = File(context.cacheDir, name)
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        return file
    }

    @Test
    fun `send persists the row SENDING with attachment rows and files - the bubble mapping`() =
        runBlocking {
            val id = sender.send("+15551234567", "look at this", listOf(staged()), subscriptionId = null)

            val row = messageDao.getById(id)!!
            assertThat(row.isOutgoing).isTrue()
            assertThat(row.deliveryStatus).isEqualTo(DeliveryStatus.SENDING)
            assertThat(row.attachmentKinds).isEqualTo("IMAGE")
            assertThat(row.body).isEqualTo("look at this")

            // Attachment rows keyed by the message id - the SAME table and
            // direction-agnostic mapping the received-MMS bubble uses.
            val rows = attachmentDao.forMessage(id)
            assertThat(rows).hasSize(1)
            assertThat(rows[0].mimeType).isEqualTo("image/jpeg")
            assertThat(mmsAttachmentFile(context.filesDir, id, rows[0].fileName).exists()).isTrue()

            // The staged compose copy was consumed.
            assertThat(File(File(context.filesDir, "mms"), "compose").listFiles().orEmpty()).isEmpty()
            assertThat(gateway.sends).hasSize(1)
        }

    @Test
    fun `chosen subscription flows to the gateway and onto the row`() =
        runBlocking {
            val id = sender.send("+15551234567", "hi", listOf(staged()), subscriptionId = 7)

            assertThat(gateway.sends.single().subscriptionId).isEqualTo(7)
            assertThat(messageDao.getById(id)?.subscriptionId).isEqualTo(7)
        }

    @Test
    fun `synchronous dispatch failure marks the row FAILED - retry flow entry`() =
        runBlocking {
            gateway.throwOnSend = true

            val id = sender.send("+15551234567", "hi", listOf(staged()))

            assertThat(messageDao.getById(id)?.deliveryStatus).isEqualTo(DeliveryStatus.FAILED)
            // The "our side" reason: the message never reached the platform,
            // so the bubble must not blame the carrier.
            assertThat(messageDao.getById(id)?.sendFailureReason).isEqualTo(SendFailureReason.DISPATCH_FAILED.name)
        }

    @Test
    fun `the gateway refusing an unreadable staged PDU marks the row FAILED on our side - never SENDING forever`() =
        runBlocking {
            gateway.unreadableOnSend = true

            val id = sender.send("+15551234567", "hi", listOf(staged()))

            assertThat(messageDao.getById(id)?.deliveryStatus).isEqualTo(DeliveryStatus.FAILED)
            assertThat(messageDao.getById(id)?.sendFailureReason).isEqualTo(SendFailureReason.DISPATCH_FAILED.name)
        }

    @Test
    fun `the gateway receives the row id with the staged file, and the file holds the PDU at hand-over`() =
        runBlocking {
            val id = sender.send("+15551234567", "hi", listOf(staged()))

            val send = gateway.sends.single()
            assertThat(send.messageId).isEqualTo(id)
            assertThat(send.pduFile.exists()).isTrue()
            assertThat(send.pduFile.length()).isGreaterThan(0L)
        }

    @Test
    fun `resend re-dispatches the SAME row with its recorded SIM`() =
        runBlocking {
            val id = sender.send("+15551234567", "hi", listOf(staged()), subscriptionId = 3)
            messageDao.setDeliveryStatus(id, DeliveryStatus.FAILED)

            sender.resend(id)

            val row = messageDao.getById(id)!!
            assertThat(row.deliveryStatus).isEqualTo(DeliveryStatus.SENDING)
            assertThat(gateway.sends).hasSize(2)
            assertThat(gateway.sends[1].subscriptionId).isEqualTo(3)
            // Still exactly one row for this thread - no duplicate bubble.
            assertThat(messageDao.getById(id)?.subscriptionId).isEqualTo(3)
        }

    @Test
    fun `a synthetic large image yields a PDU under the carrier limit - the platform will read it`() =
        runBlocking {
            val photo = staged("photo.jpg", "image/jpeg", bigNoisyImage().readBytes())
            assertThat(photo.sizeBytes).isAtMost(299_008L)

            val id = sender.send("+15551234567", "a long enough caption to matter", listOf(photo), subscriptionId = 3)

            val send = gateway.sends.single()
            assertThat(send.messageId).isEqualTo(id)
            // The reporter's PDU was 326 369 bytes against this very limit; ours fits.
            assertThat(send.pduFile.length()).isAtMost(307_200L)
            assertThat(send.pduFile.length()).isGreaterThan(photo.sizeBytes)
            assertThat(messageDao.getById(id)!!.deliveryStatus).isEqualTo(DeliveryStatus.SENDING)
            // The limit was read for the chosen SIM at hand-over.
            assertThat(carrier.asked).contains(3)
        }

    @Test
    fun `a PDU over the chosen SIM's carrier limit is refused before hand-over, never handed to the platform`() =
        runBlocking {
            // Staged while a generous SIM was chosen...
            carrier.maxMessageSizeBytes = 1_048_576
            val big = staged("big.bin", "application/octet-stream", ByteArray(400_000) { 1 })
            assertThat(big.sizeBytes).isEqualTo(400_000L)
            // ...then sent from a SIM whose carrier allows only 300 KiB.
            carrier.maxMessageSizeBytes = 307_200

            val id = sender.send("+15551234567", "hi", listOf(big), subscriptionId = 7)

            assertThat(gateway.sends).isEmpty()
            val row = messageDao.getById(id)!!
            assertThat(row.deliveryStatus).isEqualTo(DeliveryStatus.FAILED)
            assertThat(row.sendFailureReason).isEqualTo(SendFailureReason.EXCEEDS_CARRIER_LIMIT.name)
            // Nothing is left staged for the platform to stumble on.
            assertThat(AttachmentStore(context).stagingFile(id).exists()).isFalse()
        }

    @Test
    fun `attachment-only send carries kinds without a body`() =
        runBlocking {
            val id = sender.send("+15551234567", "", listOf(staged("doc.pdf", "application/pdf", ByteArray(9))))

            val row = messageDao.getById(id)!!
            assertThat(row.body).isEmpty()
            assertThat(row.attachmentKinds).isEqualTo("FILE")
        }
}
