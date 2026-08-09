package app.clearsms.sms

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.DeliveryStatus
import app.clearsms.data.db.MessageDao
import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.model.Category
import app.clearsms.testing.FakeSmsGateway
import app.clearsms.ui.common.UiPrefs
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Retrying a failed outgoing message via [SmsSender.resend]: the SAME Room
 * row flips back to SENDING (no duplicate bubble), an immediate radio
 * rejection lands it cleanly back on FAILED, and the delivery-reports
 * setting is honoured afresh on every retry.
 */
@RunWith(RobolectricTestRunner::class)
class SmsSenderResendTest {
    private lateinit var context: Context
    private lateinit var db: ClearSmsDatabase
    private lateinit var dao: MessageDao
    private lateinit var uiPrefs: UiPrefs
    private lateinit var gateway: FakeSmsGateway
    private lateinit var sender: SmsSender

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.messageDao()
        uiPrefs =
            UiPrefs(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("ui_settings", ".preferences_pb")
                },
            )
        gateway = FakeSmsGateway()
        sender = SmsSender(context, dao, TelephonyWriter(context), uiPrefs, Dispatchers.IO, gateway)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun failedOutgoing(destination: String = "+15551234567"): Long =
        runBlocking {
            dao.insert(
                MessageEntity(
                    threadId = 1L,
                    sender = destination,
                    normalizedSender = destination.removePrefix("+"),
                    body = "hello there",
                    timestamp = 1_700_000_000_000L,
                    isRead = true,
                    category = Category.PERSONAL,
                    isOutgoing = true,
                    deliveryStatus = DeliveryStatus.FAILED,
                ),
            )
        }

    @Test
    fun `retry resets the SAME row to SENDING - no new row, body and timestamp kept`() =
        runBlocking {
            val id = failedOutgoing()

            sender.resend(id)

            val rows = dao.getAll()
            assertThat(rows).hasSize(1)
            val row = rows.single()
            assertThat(row.id).isEqualTo(id)
            assertThat(row.deliveryStatus).isEqualTo(DeliveryStatus.SENDING)
            assertThat(row.body).isEqualTo("hello there")
            assertThat(row.timestamp).isEqualTo(1_700_000_000_000L)
            // The retry went through the normal multipart send path.
            val send = requireNotNull(gateway.lastSend)
            assertThat(send.destination).isEqualTo("+15551234567")
            assertThat(send.parts).containsExactly("hello there").inOrder()
        }

    @Test
    fun `retry rejected immediately by the radio returns the row to FAILED`() =
        runBlocking {
            // The gateway throws for an empty destination - the same
            // synchronous rejection airplane mode produces.
            val id = failedOutgoing(destination = "")

            sender.resend(id)

            val rows = dao.getAll()
            assertThat(rows).hasSize(1)
            assertThat(rows.single().deliveryStatus).isEqualTo(DeliveryStatus.FAILED)
        }

    @Test
    fun `retry with delivery reports off attaches no delivery intents`() =
        runBlocking {
            uiPrefs.setDeliveryReports(false)
            sender.resend(failedOutgoing())

            val send = requireNotNull(gateway.lastSend)
            assertThat(send.deliveryIntents.filterNotNull()).isEmpty()
        }

    @Test
    fun `retry with delivery reports on attaches a delivery intent per part`() =
        runBlocking {
            uiPrefs.setDeliveryReports(true)
            sender.resend(failedOutgoing())

            val send = requireNotNull(gateway.lastSend)
            assertThat(send.deliveryIntents).hasSize(send.parts.size)
            assertThat(send.deliveryIntents.filterNotNull()).hasSize(send.parts.size)
        }

    @Test
    fun `retry ignores incoming messages`() =
        runBlocking {
            val id =
                dao.insert(
                    MessageEntity(
                        threadId = 1L,
                        sender = "9876543210",
                        normalizedSender = "9876543210",
                        body = "incoming",
                        timestamp = 1L,
                        isRead = true,
                        category = Category.PERSONAL,
                        isOutgoing = false,
                    ),
                )

            sender.resend(id)

            assertThat(gateway.sends).isEmpty()
            assertThat(dao.getById(id)?.deliveryStatus).isNull()
        }
}
