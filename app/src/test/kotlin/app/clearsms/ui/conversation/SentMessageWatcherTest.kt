package app.clearsms.ui.conversation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.DeliveryStatus
import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.model.Category
import app.clearsms.mms.AttachmentStore
import app.clearsms.mms.SendFailureReason
import app.clearsms.receiver.MmsSendReportRecorder
import app.clearsms.receiver.SendReportMapper
import app.clearsms.receiver.SendReportSideEffects
import app.clearsms.receiver.SmsSentReceiver
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Send-status resolution over the PERSISTED [DeliveryStatus]: the recorded
 * radio reports decide the snackbar outcome, and a silent result window -
 * the delivery-reports-off / carrier-sends-nothing case - resolves honestly
 * to Sent (never Delivered) for SMS only: an MMS has exactly one result and
 * the platform may take minutes to produce it, so a silent window leaves it
 * SENDING - the regression for the bubble that read "Sent" for 149 s before
 * the platform reported NO_MMS_NETWORK.
 */
@RunWith(RobolectricTestRunner::class)
class SentMessageWatcherTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var watcher: SentMessageWatcher

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        watcher = SentMessageWatcher(db.messageDao(), Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun outgoing(
        status: DeliveryStatus,
        mms: Boolean = false,
    ): Long =
        db.messageDao().insert(
            MessageEntity(
                threadId = 1,
                sender = "9876543210",
                normalizedSender = "9876543210",
                body = "hi",
                timestamp = 1_000,
                category = Category.PERSONAL,
                isOutgoing = true,
                deliveryStatus = status,
                // Exactly what MmsSender persists: attachments make it an MMS.
                attachmentKinds = if (mms) "IMAGE" else null,
            ),
        )

    private fun mmsRecorder(): MmsSendReportRecorder {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sideEffects =
            object : SendReportSideEffects {
                override fun mirrorFailed(providerUri: android.net.Uri) = Unit

                override fun mirrorDelivered(providerUri: android.net.Uri) = Unit

                override fun notifyFailure(
                    destination: String,
                    threadId: Long?,
                    messageId: Long?,
                ) = Unit
            }
        return MmsSendReportRecorder(db.messageDao(), AttachmentStore(context), sideEffects)
    }

    @Test
    fun `a recorded failure resolves to failed`() =
        runBlocking {
            val id = outgoing(DeliveryStatus.FAILED)

            assertThat(watcher.await(id)).isEqualTo(SendStatus.FAILED)
        }

    @Test
    fun `a sent report resolves to sent`() =
        runBlocking {
            val id = outgoing(DeliveryStatus.SENT)

            assertThat(watcher.await(id)).isEqualTo(SendStatus.SENT)
        }

    @Test
    fun `a delivery report also resolves the snackbar to sent`() =
        runBlocking {
            val id = outgoing(DeliveryStatus.DELIVERED)

            assertThat(watcher.await(id)).isEqualTo(SendStatus.SENT)
        }

    @Test
    fun `no report within the window resolves to sent and promotes the row`() =
        runBlocking {
            // Delivery reports off (or the carrier returned nothing): the row
            // stays SENDING, so the window closes the send as Sent - the
            // status the brief mandates instead of a fabricated Delivered.
            val id = outgoing(DeliveryStatus.SENDING)

            val status = watcher.await(id, windowMs = 50)

            assertThat(status).isEqualTo(SendStatus.SENT)
            assertThat(db.messageDao().getById(id)!!.deliveryStatus).isEqualTo(DeliveryStatus.SENT)
        }

    // region MMS: time proves nothing

    @Test
    fun `an MMS with no result within the window stays SENDING and is never promoted`() =
        runBlocking {
            // The operator's device: handover at 02:43:51, the platform's
            // result at 02:46:20. Whatever the window says, the row must not
            // read Sent in between.
            val id = outgoing(DeliveryStatus.SENDING, mms = true)

            val status = watcher.await(id, windowMs = 50)

            assertThat(status).isEqualTo(SendStatus.SENDING)
            assertThat(db.messageDao().getById(id)!!.deliveryStatus).isEqualTo(DeliveryStatus.SENDING)
        }

    @Test
    fun `an MMS result within the window resolves like an SMS one`() =
        runBlocking {
            val sent = outgoing(DeliveryStatus.SENT, mms = true)
            val failed = outgoing(DeliveryStatus.FAILED, mms = true)

            assertThat(watcher.await(sent, windowMs = 50)).isEqualTo(SendStatus.SENT)
            assertThat(watcher.await(failed, windowMs = 50)).isEqualTo(SendStatus.FAILED)
        }

    @Test
    fun `the late MMS failure lands on Not sent with its reason and ends the wait`() =
        runBlocking {
            val id = outgoing(DeliveryStatus.SENDING, mms = true)
            assertThat(watcher.await(id, windowMs = 50)).isEqualTo(SendStatus.SENDING)

            val result = async(Dispatchers.IO) { watcher.awaitResult(id) }
            launch(Dispatchers.IO) {
                delay(100)
                // The receiver's report, minutes later on a real device.
                mmsRecorder().record(id, "9876543210", succeeded = false, failureReason = SendFailureReason.NO_MMS_NETWORK)
            }

            assertThat(result.await()).isEqualTo(SendStatus.FAILED)
            val row = db.messageDao().getById(id)!!
            assertThat(row.deliveryStatus).isEqualTo(DeliveryStatus.FAILED)
            assertThat(row.sendFailureReason).isEqualTo("NO_MMS_NETWORK")
        }

    @Test
    fun `the late MMS OK is the only thing that promotes the row to SENT`() =
        runBlocking {
            val id = outgoing(DeliveryStatus.SENDING, mms = true)
            assertThat(watcher.await(id, windowMs = 50)).isEqualTo(SendStatus.SENDING)
            assertThat(db.messageDao().getById(id)!!.deliveryStatus).isEqualTo(DeliveryStatus.SENDING)

            val result = async(Dispatchers.IO) { watcher.awaitResult(id) }
            launch(Dispatchers.IO) {
                delay(100)
                mmsRecorder().record(id, "9876543210", succeeded = true)
            }

            assertThat(result.await()).isEqualTo(SendStatus.SENT)
            assertThat(db.messageDao().getById(id)!!.deliveryStatus).isEqualTo(DeliveryStatus.SENT)
        }

    // endregion

    @Test
    fun `radio reports map to the statuses the receiver records`() {
        assertThat(SendReportMapper.statusFor(SmsSentReceiver.ACTION_SMS_SENT, resultOk = true))
            .isEqualTo(DeliveryStatus.SENT)
        assertThat(SendReportMapper.statusFor(SmsSentReceiver.ACTION_SMS_SENT, resultOk = false))
            .isEqualTo(DeliveryStatus.FAILED)
        assertThat(SendReportMapper.statusFor(SmsSentReceiver.ACTION_SMS_DELIVERED, resultOk = true))
            .isEqualTo(DeliveryStatus.DELIVERED)
        assertThat(SendReportMapper.statusFor("unknown", resultOk = true)).isNull()
    }
}
