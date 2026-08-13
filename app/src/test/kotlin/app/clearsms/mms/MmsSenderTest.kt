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
        val subscriptionId: Int?,
        val pduFile: File,
    )

    val sends = mutableListOf<Send>()
    var throwOnSend: Boolean = false

    override fun sendMultimediaMessage(
        subscriptionId: Int?,
        pduFile: File,
        sentIntent: PendingIntent,
    ) {
        if (throwOnSend) throw IllegalStateException("radio unavailable")
        sends += Send(subscriptionId, pduFile)
    }
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
        stager = OutgoingAttachmentStager(context)
        sender =
            MmsSender(
                context,
                messageDao,
                attachmentDao,
                AttachmentStore(context),
                stager,
                gateway,
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
        return requireNotNull(stager.stage(uri))
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
    fun `attachment-only send carries kinds without a body`() =
        runBlocking {
            val id = sender.send("+15551234567", "", listOf(staged("doc.pdf", "application/pdf", ByteArray(9))))

            val row = messageDao.getById(id)!!
            assertThat(row.body).isEmpty()
            assertThat(row.attachmentKinds).isEqualTo("FILE")
        }
}
