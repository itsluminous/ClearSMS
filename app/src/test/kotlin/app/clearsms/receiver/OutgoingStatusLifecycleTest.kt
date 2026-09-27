package app.clearsms.receiver

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.DeliveryStatus
import app.clearsms.data.db.MessageDao
import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.model.Category
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The outgoing send lifecycle, exercised through [SendReportRecorder] against
 * a real in-memory Room database - the same aggregation the broadcast
 * receiver runs on radio reports:
 *
 * SENDING → SENT (sent report) → DELIVERED (delivery report, per part) and
 * → FAILED (any part's failure, sticky), with multipart worst-part rules -
 * plus the acknowledgement time (GitHub #44): the instant the device
 * processed the COMPLETING delivery report is persisted on the row as the
 * delivery time, and only then.
 */
@RunWith(RobolectricTestRunner::class)
class OutgoingStatusLifecycleTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var dao: MessageDao
    private lateinit var sideEffects: FakeSideEffects
    private lateinit var recorder: SendReportRecorder

    private val systemSmsId = 42L
    private val providerUri: Uri = "content://sms/$systemSmsId".toUri()

    private class FakeSideEffects : SendReportSideEffects {
        var failedMirrors = 0
        var deliveredMirrors = 0
        var failureNotifications = 0
        var lastFailureTarget: Pair<Long?, Long?>? = null

        override fun mirrorFailed(providerUri: Uri) {
            failedMirrors++
        }

        override fun mirrorDelivered(providerUri: Uri) {
            deliveredMirrors++
        }

        override fun notifyFailure(
            destination: String,
            threadId: Long?,
            messageId: Long?,
        ) {
            failureNotifications++
            lastFailureTarget = threadId to messageId
        }
    }

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.messageDao()
        sideEffects = FakeSideEffects()
        recorder = SendReportRecorder(dao, sideEffects)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun outgoing(partCount: Int) =
        runBlocking {
            val id =
                dao.insert(
                    MessageEntity(
                        threadId = 1L,
                        sender = "+15551234567",
                        normalizedSender = "15551234567",
                        body = "hello",
                        timestamp = 1L,
                        isRead = true,
                        category = Category.PERSONAL,
                        systemSmsId = systemSmsId,
                        isOutgoing = true,
                        deliveryStatus = DeliveryStatus.SENDING,
                    ),
                )
            dao.setPartCount(id, partCount)
            id
        }

    private suspend fun status(id: Long) = dao.getById(id)?.deliveryStatus

    private fun report(
        status: DeliveryStatus,
        partIndex: Int = 0,
        partCount: Int = 1,
    ) = SendPartReport(status, providerUri, "+15551234567", partIndex, partCount)

    @Test
    fun `single part - SENDING promotes to SENT on the sent report`() =
        runBlocking {
            val id = outgoing(partCount = 1)
            recorder.record(report(DeliveryStatus.SENT))
            assertThat(status(id)).isEqualTo(DeliveryStatus.SENT)
        }

    @Test
    fun `single part - SENT promotes to DELIVERED on the delivery report and mirrors the provider`() =
        runBlocking {
            val id = outgoing(partCount = 1)
            recorder.record(report(DeliveryStatus.SENT))
            recorder.record(report(DeliveryStatus.DELIVERED))
            assertThat(status(id)).isEqualTo(DeliveryStatus.DELIVERED)
            assertThat(sideEffects.deliveredMirrors).isEqualTo(1)
        }

    @Test
    fun `single part - a failure report marks FAILED and notifies once`() =
        runBlocking {
            val id = outgoing(partCount = 1)
            recorder.record(report(DeliveryStatus.FAILED))
            assertThat(status(id)).isEqualTo(DeliveryStatus.FAILED)
            assertThat(sideEffects.failedMirrors).isEqualTo(1)
            assertThat(sideEffects.failureNotifications).isEqualTo(1)
        }

    @Test
    fun `delivery reports OFF - the terminal state is SENT, never DELIVERED`() =
        runBlocking {
            // With the setting off SmsSender attaches no delivery intents, so
            // no DELIVERED report can ever arrive: only the sent report lands.
            val id = outgoing(partCount = 1)
            recorder.record(report(DeliveryStatus.SENT))
            assertThat(status(id)).isEqualTo(DeliveryStatus.SENT)
            assertThat(sideEffects.deliveredMirrors).isEqualTo(0)
        }

    @Test
    fun `a late sent report never downgrades a DELIVERED message`() =
        runBlocking {
            val id = outgoing(partCount = 1)
            recorder.record(report(DeliveryStatus.SENT))
            recorder.record(report(DeliveryStatus.DELIVERED))
            recorder.record(report(DeliveryStatus.SENT))
            assertThat(status(id)).isEqualTo(DeliveryStatus.DELIVERED)
        }

    @Test
    fun `multipart - only the LAST part's sent report promotes to SENT`() =
        runBlocking {
            val id = outgoing(partCount = 3)
            recorder.record(report(DeliveryStatus.SENT, partIndex = 0, partCount = 3))
            assertThat(status(id)).isEqualTo(DeliveryStatus.SENDING)
            recorder.record(report(DeliveryStatus.SENT, partIndex = 2, partCount = 3))
            assertThat(status(id)).isEqualTo(DeliveryStatus.SENT)
        }

    @Test
    fun `multipart worst-part - any part failing fails the whole message, even after SENT`() =
        runBlocking {
            val id = outgoing(partCount = 3)
            recorder.record(report(DeliveryStatus.SENT, partIndex = 2, partCount = 3))
            assertThat(status(id)).isEqualTo(DeliveryStatus.SENT)
            // An out-of-order failure of an earlier part arrives afterwards.
            recorder.record(report(DeliveryStatus.FAILED, partIndex = 1, partCount = 3))
            assertThat(status(id)).isEqualTo(DeliveryStatus.FAILED)
        }

    @Test
    fun `multipart worst-part - several failing parts notify the user exactly once`() =
        runBlocking {
            outgoing(partCount = 3)
            recorder.record(report(DeliveryStatus.FAILED, partIndex = 0, partCount = 3))
            recorder.record(report(DeliveryStatus.FAILED, partIndex = 1, partCount = 3))
            assertThat(sideEffects.failureNotifications).isEqualTo(1)
            assertThat(sideEffects.failedMirrors).isEqualTo(1)
        }

    @Test
    fun `multipart worst-part - DELIVERED only when EVERY part has a delivery report`() =
        runBlocking {
            val id = outgoing(partCount = 3)
            recorder.record(report(DeliveryStatus.SENT, partIndex = 2, partCount = 3))
            recorder.record(report(DeliveryStatus.DELIVERED, partIndex = 0, partCount = 3))
            recorder.record(report(DeliveryStatus.DELIVERED, partIndex = 1, partCount = 3))
            // Two of three parts delivered: honestly still SENT.
            assertThat(status(id)).isEqualTo(DeliveryStatus.SENT)
            assertThat(sideEffects.deliveredMirrors).isEqualTo(0)

            recorder.record(report(DeliveryStatus.DELIVERED, partIndex = 2, partCount = 3))
            assertThat(status(id)).isEqualTo(DeliveryStatus.DELIVERED)
            assertThat(sideEffects.deliveredMirrors).isEqualTo(1)
        }

    @Test
    fun `multipart worst-part - a failed part blocks DELIVERED even if all parts report delivery`() =
        runBlocking {
            val id = outgoing(partCount = 2)
            recorder.record(report(DeliveryStatus.FAILED, partIndex = 0, partCount = 2))
            recorder.record(report(DeliveryStatus.DELIVERED, partIndex = 0, partCount = 2))
            recorder.record(report(DeliveryStatus.DELIVERED, partIndex = 1, partCount = 2))
            assertThat(status(id)).isEqualTo(DeliveryStatus.FAILED)
            assertThat(sideEffects.deliveredMirrors).isEqualTo(0)
        }

    @Test
    fun `resend resets the delivered-part tally so old reports cannot complete a new dispatch`() =
        runBlocking {
            val id = outgoing(partCount = 2)
            recorder.record(report(DeliveryStatus.DELIVERED, partIndex = 0, partCount = 2))
            dao.resetForResend(id, systemSmsId)
            assertThat(status(id)).isEqualTo(DeliveryStatus.SENDING)
            // One report after the reset is 1 of 2 - not delivered.
            recorder.record(report(DeliveryStatus.DELIVERED, partIndex = 1, partCount = 2))
            assertThat(status(id)).isNotEqualTo(DeliveryStatus.DELIVERED)
        }

    // region acknowledgement time (GitHub #44)

    private suspend fun deliveredAt(id: Long) = dao.getById(id)?.deliveredAt

    @Test
    fun `delivery report - the time the device processed it is PERSISTED as the delivery time`() =
        runBlocking {
            val id = outgoing(partCount = 1)
            recorder.record(report(DeliveryStatus.SENT), acknowledgedAtMs = 1_000L)
            assertThat(deliveredAt(id)).isNull()

            recorder.record(report(DeliveryStatus.DELIVERED), acknowledgedAtMs = 1_700_000_012_345L)

            // Read back from the row, not from memory: this is what survives
            // process death alongside the DELIVERED status.
            val row = dao.getById(id)!!
            assertThat(row.deliveryStatus).isEqualTo(DeliveryStatus.DELIVERED)
            assertThat(row.deliveredAt).isEqualTo(1_700_000_012_345L)
        }

    @Test
    fun `sent report only - no acknowledgement time is ever recorded`() =
        runBlocking {
            val id = outgoing(partCount = 1)
            recorder.record(report(DeliveryStatus.SENT), acknowledgedAtMs = 1_700_000_012_345L)
            assertThat(status(id)).isEqualTo(DeliveryStatus.SENT)
            assertThat(deliveredAt(id)).isNull()
        }

    @Test
    fun `multipart - only the COMPLETING part's report stamps the time, with ITS processing instant`() =
        runBlocking {
            val id = outgoing(partCount = 3)
            recorder.record(report(DeliveryStatus.SENT, partIndex = 2, partCount = 3), acknowledgedAtMs = 10L)
            recorder.record(report(DeliveryStatus.DELIVERED, partIndex = 0, partCount = 3), acknowledgedAtMs = 20L)
            recorder.record(report(DeliveryStatus.DELIVERED, partIndex = 1, partCount = 3), acknowledgedAtMs = 30L)
            // Two of three delivered: no delivery time for a partial delivery.
            assertThat(deliveredAt(id)).isNull()

            recorder.record(report(DeliveryStatus.DELIVERED, partIndex = 2, partCount = 3), acknowledgedAtMs = 40L)
            assertThat(status(id)).isEqualTo(DeliveryStatus.DELIVERED)
            assertThat(deliveredAt(id)).isEqualTo(40L)
        }

    @Test
    fun `a duplicate late delivery report does not move the recorded time`() =
        runBlocking {
            val id = outgoing(partCount = 1)
            recorder.record(report(DeliveryStatus.DELIVERED), acknowledgedAtMs = 100L)
            recorder.record(report(DeliveryStatus.DELIVERED), acknowledgedAtMs = 200L)
            assertThat(deliveredAt(id)).isEqualTo(100L)
        }

    @Test
    fun `a later part failure demotes to FAILED and drops the time - a failed row never carries one`() =
        runBlocking {
            val id = outgoing(partCount = 2)
            recorder.record(report(DeliveryStatus.DELIVERED, partIndex = 0, partCount = 2), acknowledgedAtMs = 100L)
            recorder.record(report(DeliveryStatus.DELIVERED, partIndex = 1, partCount = 2), acknowledgedAtMs = 200L)
            assertThat(deliveredAt(id)).isEqualTo(200L)

            recorder.record(report(DeliveryStatus.FAILED, partIndex = 1, partCount = 2), acknowledgedAtMs = 300L)
            assertThat(status(id)).isEqualTo(DeliveryStatus.FAILED)
            assertThat(deliveredAt(id)).isNull()
        }

    @Test
    fun `a FAILED row never gains a time from a straggling delivery report`() =
        runBlocking {
            val id = outgoing(partCount = 1)
            recorder.record(report(DeliveryStatus.FAILED), acknowledgedAtMs = 100L)
            recorder.record(report(DeliveryStatus.DELIVERED), acknowledgedAtMs = 200L)
            assertThat(status(id)).isEqualTo(DeliveryStatus.FAILED)
            assertThat(deliveredAt(id)).isNull()
        }

    @Test
    fun `resend clears the old acknowledgement time - the new dispatch earns its own`() =
        runBlocking {
            val id = outgoing(partCount = 1)
            recorder.record(report(DeliveryStatus.DELIVERED), acknowledgedAtMs = 100L)
            assertThat(deliveredAt(id)).isEqualTo(100L)

            dao.resetForResend(id, systemSmsId)
            assertThat(deliveredAt(id)).isNull()

            recorder.record(report(DeliveryStatus.DELIVERED), acknowledgedAtMs = 500L)
            assertThat(deliveredAt(id)).isEqualTo(500L)
        }

    // endregion
}
