package app.clearsms.ui.conversation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.DeliveryStatus
import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.model.Category
import app.clearsms.receiver.SendReportMapper
import app.clearsms.receiver.SmsSentReceiver
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
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
 * to Sent (never Delivered).
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

    private suspend fun outgoing(status: DeliveryStatus): Long =
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
            ),
        )

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
