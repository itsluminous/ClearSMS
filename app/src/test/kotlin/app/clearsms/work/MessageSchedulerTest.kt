package app.clearsms.work

import android.app.AlarmManager
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.DeliveryStatus
import app.clearsms.data.db.MessageDao
import app.clearsms.sms.SmsSender
import app.clearsms.sms.TelephonyWriter
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import java.io.File

/**
 * The scheduled-message lifecycle: durable SCHEDULED rows, exact alarms
 * with an honest inexact fallback, firing through the normal send path
 * exactly once, cancel/edit, and boot re-arming with immediate overdue
 * dispatch.
 */
@RunWith(RobolectricTestRunner::class)
class MessageSchedulerTest {
    private lateinit var context: Context
    private lateinit var db: ClearSmsDatabase
    private lateinit var dao: MessageDao
    private lateinit var scheduler: MessageScheduler
    private lateinit var gateway: FakeSmsGateway
    private lateinit var smsSender: SmsSender
    private lateinit var shadowAlarms: ShadowAlarmManager

    private val future = System.currentTimeMillis() + 120_000L

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
        smsSender = SmsSender(context, dao, TelephonyWriter(context), uiPrefs, Dispatchers.IO, gateway)
        scheduler = MessageScheduler(dao, smsSender, ScheduledSendAlarms(context), Dispatchers.IO)
        shadowAlarms = shadowOf(requireNotNull(context.getSystemService(AlarmManager::class.java)))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `schedule persists a SCHEDULED row and registers its alarm`() =
        runBlocking<Unit> {
            val id = scheduler.schedule("+15551234567", "later!", subscriptionId = 7, scheduledAtMs = future)

            val row = requireNotNull(dao.getById(id))
            assertThat(row.deliveryStatus).isEqualTo(DeliveryStatus.SCHEDULED)
            assertThat(row.scheduledAt).isEqualTo(future)
            assertThat(row.timestamp).isEqualTo(future)
            assertThat(row.isOutgoing).isTrue()
            assertThat(row.subscriptionId).isEqualTo(7)
            // Nothing is in the provider yet - the message was never sent.
            assertThat(row.systemSmsId).isNull()
            val alarm = requireNotNull(shadowAlarms.peekNextScheduledAlarm())
            assertThat(alarm.triggerAtMs).isEqualTo(future)
        }

    @Test
    fun `fire dispatches through the normal send path exactly once`() =
        runBlocking<Unit> {
            val id = scheduler.schedule("+15551234567", "later!", subscriptionId = null, scheduledAtMs = future)

            assertThat(smsSender.sendScheduled(id)).isTrue()
            // A duplicate alarm loses the compare-and-set claim: no re-send.
            assertThat(smsSender.sendScheduled(id)).isFalse()

            val row = requireNotNull(dao.getById(id))
            assertThat(row.deliveryStatus).isEqualTo(DeliveryStatus.SENDING)
            assertThat(row.scheduledAt).isNull()
            assertThat(row.timestamp).isAtLeast(future - 120_000L)
            // The radio actually received the message.
            assertThat(requireNotNull(gateway.lastSend).parts).containsExactly("later!")
        }

    @Test
    fun `cancel clears the row and its alarm`() =
        runBlocking<Unit> {
            val id = scheduler.schedule("+15551234567", "later!", subscriptionId = null, scheduledAtMs = future)
            assertThat(shadowAlarms.scheduledAlarms).hasSize(1)

            scheduler.cancel(id)

            assertThat(dao.getById(id)).isNull()
            assertThat(shadowAlarms.scheduledAlarms).isEmpty()
        }

    @Test
    fun `edit moves the fire time and re-arms the alarm`() =
        runBlocking<Unit> {
            val id = scheduler.schedule("+15551234567", "later!", subscriptionId = null, scheduledAtMs = future)
            val newTime = future + 3_600_000L

            scheduler.reschedule(id, newTime)

            val row = requireNotNull(dao.getById(id))
            assertThat(row.scheduledAt).isEqualTo(newTime)
            assertThat(row.timestamp).isEqualTo(newTime)
            assertThat(row.deliveryStatus).isEqualTo(DeliveryStatus.SCHEDULED)
            val alarm = requireNotNull(shadowAlarms.peekNextScheduledAlarm())
            assertThat(alarm.triggerAtMs).isEqualTo(newTime)
        }

    @Test
    fun `rearmAll re-registers pending schedules and fires overdue ones immediately`() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val pendingId = scheduler.schedule("+15551234567", "future", subscriptionId = null, scheduledAtMs = now + 60_000L)
            val overdueId = scheduler.schedule("+15559876543", "overdue", subscriptionId = null, scheduledAtMs = now + 30_000L)

            // "Now" is past the overdue schedule but before the pending one.
            scheduler.rearmAll(nowMs = now + 45_000L)

            // Overdue: dispatched immediately (a boot-missed message sends).
            assertThat(dao.getById(overdueId)?.deliveryStatus).isEqualTo(DeliveryStatus.SENDING)
            // Pending: still scheduled, alarm (re-)registered at its time.
            assertThat(dao.getById(pendingId)?.deliveryStatus).isEqualTo(DeliveryStatus.SCHEDULED)
            assertThat(shadowAlarms.scheduledAlarms.map { it.triggerAtMs }).contains(now + 60_000L)
        }

    @Test
    fun `exact-alarm permission denied falls back to an inexact alarm`() {
        val alarms = ScheduledSendAlarms(context)

        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertThat(alarms.arm(1L, future)).isTrue()

        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        // Still registered - just inexact; a late send beats no send.
        assertThat(alarms.arm(2L, future + 1)).isFalse()
        assertThat(shadowAlarms.scheduledAlarms).hasSize(2)
    }

    @Test
    fun `cancelling a schedule that already fired leaves the sent row alone`() =
        runBlocking<Unit> {
            val id = scheduler.schedule("+15551234567", "later!", subscriptionId = null, scheduledAtMs = future)
            smsSender.sendScheduled(id)

            scheduler.cancel(id)

            // The row is no longer SCHEDULED, so cancel must not delete it.
            assertThat(dao.getById(id)?.deliveryStatus).isEqualTo(DeliveryStatus.SENDING)
        }
}
