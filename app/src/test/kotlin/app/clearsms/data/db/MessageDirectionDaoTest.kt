package app.clearsms.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.domain.model.Category
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Direction and delivery status are persisted per row and transition safely. */
@RunWith(RobolectricTestRunner::class)
class MessageDirectionDaoTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var dao: MessageDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.messageDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun message(
        outgoing: Boolean,
        status: DeliveryStatus? = null,
        systemSmsId: Long? = null,
    ) = MessageEntity(
        threadId = 1L,
        sender = "9876543210",
        normalizedSender = "9876543210",
        body = "hello",
        timestamp = 1_000L,
        category = Category.PERSONAL,
        isOutgoing = outgoing,
        deliveryStatus = status,
        systemSmsId = systemSmsId,
    )

    @Test
    fun `incoming and outgoing rows keep their persisted direction`() =
        runBlocking {
            val incomingId = dao.insert(message(outgoing = false))
            val outgoingId = dao.insert(message(outgoing = true, status = DeliveryStatus.SENDING))

            val incoming = dao.getById(incomingId)!!
            val outgoing = dao.getById(outgoingId)!!
            assertThat(incoming.isOutgoing).isFalse()
            assertThat(incoming.deliveryStatus).isNull()
            assertThat(outgoing.isOutgoing).isTrue()
            assertThat(outgoing.deliveryStatus).isEqualTo(DeliveryStatus.SENDING)
        }

    @Test
    fun `sent report promotes sending to sent by system id`() =
        runBlocking {
            val id = dao.insert(message(outgoing = true, status = DeliveryStatus.SENDING, systemSmsId = 42L))

            dao.promoteDeliveryStatusBySystemId(42L, expected = DeliveryStatus.SENDING, newStatus = DeliveryStatus.SENT)

            assertThat(dao.getById(id)!!.deliveryStatus).isEqualTo(DeliveryStatus.SENT)
        }

    @Test
    fun `late sent report never downgrades a delivered message`() =
        runBlocking {
            val id = dao.insert(message(outgoing = true, status = DeliveryStatus.DELIVERED, systemSmsId = 42L))

            dao.promoteDeliveryStatusBySystemId(42L, expected = DeliveryStatus.SENDING, newStatus = DeliveryStatus.SENT)

            assertThat(dao.getById(id)!!.deliveryStatus).isEqualTo(DeliveryStatus.DELIVERED)
        }

    @Test
    fun `delivery report marks the row delivered`() =
        runBlocking {
            val id = dao.insert(message(outgoing = true, status = DeliveryStatus.SENT, systemSmsId = 42L))

            dao.setDeliveryStatusBySystemId(42L, DeliveryStatus.DELIVERED)

            assertThat(dao.getById(id)!!.deliveryStatus).isEqualTo(DeliveryStatus.DELIVERED)
        }

    @Test
    fun `resend resets a failed row to sending on a new provider row`() =
        runBlocking {
            val id = dao.insert(message(outgoing = true, status = DeliveryStatus.FAILED, systemSmsId = 42L))

            dao.resetForResend(id, systemSmsId = 43L)

            val row = dao.getById(id)!!
            assertThat(row.deliveryStatus).isEqualTo(DeliveryStatus.SENDING)
            assertThat(row.systemSmsId).isEqualTo(43L)
        }

    @Test
    fun `observeDeliveryStatus emits the persisted status`() =
        runBlocking {
            val id = dao.insert(message(outgoing = true, status = DeliveryStatus.SENDING))

            assertThat(dao.observeDeliveryStatus(id).first()).isEqualTo(DeliveryStatus.SENDING)
            dao.setDeliveryStatus(id, DeliveryStatus.FAILED)
            assertThat(dao.observeDeliveryStatus(id).first()).isEqualTo(DeliveryStatus.FAILED)
        }
}
