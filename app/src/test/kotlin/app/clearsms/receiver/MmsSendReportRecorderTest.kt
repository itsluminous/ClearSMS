package app.clearsms.receiver

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.DeliveryStatus
import app.clearsms.data.db.MessageDao
import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.model.Category
import app.clearsms.mms.AttachmentStore
import app.clearsms.mms.SendFailureReason
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

private class RecordingSideEffects : SendReportSideEffects {
    val failures = mutableListOf<String>()

    override fun mirrorFailed(providerUri: Uri) = Unit

    override fun mirrorDelivered(providerUri: Uri) = Unit

    override fun notifyFailure(destination: String) {
        failures += destination
    }
}

/**
 * The MMS send lifecycle through the receiver's recorder: RESULT_OK
 * promotes SENDING -> SENT; a failure lands FAILED exactly once with one
 * user notification; a failure recorded before a late OK wins (the
 * compare-and-set); the staged PDU is cleaned up either way.
 */
@RunWith(RobolectricTestRunner::class)
class MmsSendReportRecorderTest {
    private lateinit var context: Context
    private lateinit var db: ClearSmsDatabase
    private lateinit var dao: MessageDao
    private lateinit var store: AttachmentStore
    private lateinit var sideEffects: RecordingSideEffects
    private lateinit var recorder: MmsSendReportRecorder

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.messageDao()
        store = AttachmentStore(context)
        sideEffects = RecordingSideEffects()
        recorder = MmsSendReportRecorder(dao, store, sideEffects)
    }

    @After
    fun tearDown() {
        db.close()
        File(context.filesDir, "mms").deleteRecursively()
    }

    private fun sendingRow(): Long =
        runBlocking {
            dao.insert(
                MessageEntity(
                    threadId = 1L,
                    sender = "+15551234567",
                    normalizedSender = "15551234567",
                    body = "hi",
                    timestamp = 1L,
                    category = Category.PERSONAL,
                    isOutgoing = true,
                    deliveryStatus = DeliveryStatus.SENDING,
                ),
            )
        }

    @Test
    fun `RESULT_OK promotes SENDING to SENT and deletes the staged pdu`() =
        runBlocking {
            val id = sendingRow()
            store.stagingFile(id).writeBytes(byteArrayOf(1))

            recorder.record(id, "+15551234567", succeeded = true)

            assertThat(dao.getById(id)?.deliveryStatus).isEqualTo(DeliveryStatus.SENT)
            assertThat(store.stagingFile(id).exists()).isFalse()
            assertThat(sideEffects.failures).isEmpty()
        }

    @Test
    fun `failure marks FAILED and notifies exactly once`() =
        runBlocking<Unit> {
            val id = sendingRow()

            recorder.record(id, "+15551234567", succeeded = false)
            recorder.record(id, "+15551234567", succeeded = false)

            assertThat(dao.getById(id)?.deliveryStatus).isEqualTo(DeliveryStatus.FAILED)
            assertThat(sideEffects.failures).containsExactly("+15551234567")
        }

    @Test
    fun `failure records the reason and resend clears it`() =
        runBlocking<Unit> {
            val id = sendingRow()

            recorder.record(id, "+15551234567", succeeded = false, failureReason = SendFailureReason.NO_MMS_NETWORK)
            assertThat(dao.getById(id)?.sendFailureReason).isEqualTo("NO_MMS_NETWORK")

            dao.resetForResend(id, systemSmsId = null)
            assertThat(dao.getById(id)?.sendFailureReason).isNull()
        }

    @Test
    fun `a recorded failure is not overwritten by a late OK`() =
        runBlocking {
            val id = sendingRow()
            recorder.record(id, "+15551234567", succeeded = false)

            recorder.record(id, "+15551234567", succeeded = true)

            assertThat(dao.getById(id)?.deliveryStatus).isEqualTo(DeliveryStatus.FAILED)
        }
}
