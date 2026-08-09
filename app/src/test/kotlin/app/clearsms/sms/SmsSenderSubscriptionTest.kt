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
 * SIM provenance on the send path: the chosen subscription is recorded on
 * the outgoing row, and a resend reuses the row's SIM instead of silently
 * switching.
 */
@RunWith(RobolectricTestRunner::class)
class SmsSenderSubscriptionTest {
    private lateinit var context: Context
    private lateinit var db: ClearSmsDatabase
    private lateinit var dao: MessageDao
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
        val uiPrefs =
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

    @Test
    fun `send records the chosen subscription on the outgoing row`() =
        runBlocking {
            val id = sender.send("+15551234567", "hello", subscriptionId = 7)

            assertThat(dao.getById(id)?.subscriptionId).isEqualTo(7)
            assertThat(gateway.lastSend?.subscriptionId).isEqualTo(7)
        }

    @Test
    fun `send without a choice records no subscription - default manager path`() =
        runBlocking {
            val id = sender.send("+15551234567", "hello")

            assertThat(dao.getById(id)?.subscriptionId).isNull()
            assertThat(gateway.sends).hasSize(1)
            assertThat(gateway.lastSend?.subscriptionId).isNull()
        }

    @Test
    fun `resend keeps the row's SIM`() =
        runBlocking {
            val id =
                dao.insert(
                    MessageEntity(
                        threadId = 1L,
                        sender = "+15551234567",
                        normalizedSender = "5551234567",
                        body = "hello",
                        timestamp = 1_700_000_000_000L,
                        isRead = true,
                        category = Category.PERSONAL,
                        isOutgoing = true,
                        deliveryStatus = DeliveryStatus.FAILED,
                        subscriptionId = 7,
                    ),
                )

            sender.resend(id)

            val row = dao.getById(id)
            assertThat(row?.subscriptionId).isEqualTo(7)
            assertThat(row?.deliveryStatus).isEqualTo(DeliveryStatus.SENDING)
            assertThat(gateway.lastSend?.subscriptionId).isEqualTo(7)
        }
}
