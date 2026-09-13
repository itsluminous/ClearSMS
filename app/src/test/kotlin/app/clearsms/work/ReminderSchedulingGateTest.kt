package app.clearsms.work

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ReminderDao
import app.clearsms.data.db.ReminderEntity
import app.clearsms.domain.model.EnabledSections
import app.clearsms.domain.model.ReminderType
import app.clearsms.testing.FakeSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

/**
 * "Alerts off" must stop the WAKE-UPS, not merely drop the notification at
 * fire time: with the section disabled no bill-due alarm is registered at
 * all - neither for a fresh bill message ([ReminderAlarmScheduler
 * .scheduleForMessage]) nor by the after-boot re-registration
 * ([ReminderAlarmScheduler.rescheduleAll]) - and disabling the section
 * cancels the alarms registered while it was on
 * ([ReminderAlarmScheduler.cancelUpcoming], called by the settings toggle).
 */
@RunWith(RobolectricTestRunner::class)
class ReminderSchedulingGateTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = FakeSettingsRepository()

    private val reminder =
        ReminderEntity(
            id = 1L,
            type = ReminderType.CREDIT_CARD,
            dueDate = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(7),
            totalDue = 100.0,
            rawSmsId = 11L,
            createdAt = System.currentTimeMillis(),
        )

    private val dao =
        object : ReminderDao {
            val upcoming = MutableStateFlow(listOf(reminder))

            override fun observeAll(): Flow<List<ReminderEntity>> = upcoming

            override fun observeUpcoming(nowMs: Long): Flow<List<ReminderEntity>> = upcoming

            override suspend fun findByRawSmsId(rawSmsId: Long): ReminderEntity? = upcoming.value.firstOrNull { it.rawSmsId == rawSmsId }

            override suspend fun getAll(): List<ReminderEntity> = upcoming.value

            override suspend fun insert(reminder: ReminderEntity): Long = reminder.id

            override suspend fun insertAll(reminders: List<ReminderEntity>) = Unit

            override suspend fun setDismissed(
                ids: List<Long>,
                dismissedAt: Long?,
            ) = Unit

            override suspend fun deleteByIds(ids: List<Long>) = Unit

            override suspend fun deleteByRawSmsId(rawSmsId: Long) = Unit

            override suspend fun deleteAll() = Unit
        }

    private val scheduler = ReminderAlarmScheduler(context, dao, settings)

    private fun scheduledAlarmCount(): Int = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size

    private fun alerts(enabled: Boolean) {
        settings.enabledSections.value = EnabledSections(alerts = enabled)
    }

    @Test
    fun `a bill message schedules its alarm while Alerts is on`() =
        runTest {
            alerts(true)
            scheduler.scheduleForMessage(11L)
            assertThat(scheduledAlarmCount()).isEqualTo(1)
        }

    @Test
    fun `no alarm is requested for a bill message while Alerts is off`() =
        runTest {
            alerts(false)
            scheduler.scheduleForMessage(11L)
            assertThat(scheduledAlarmCount()).isEqualTo(0)
        }

    @Test
    fun `the after-boot re-registration is also gated by the Alerts flag`() =
        runTest {
            alerts(false)
            scheduler.rescheduleAll()
            assertThat(scheduledAlarmCount()).isEqualTo(0)
            alerts(true)
            scheduler.rescheduleAll()
            assertThat(scheduledAlarmCount()).isEqualTo(1)
        }

    @Test
    fun `disabling Alerts cancels the alarms registered while it was on`() =
        runTest {
            alerts(true)
            scheduler.rescheduleAll()
            assertThat(scheduledAlarmCount()).isEqualTo(1)
            // The settings toggle pairs setAlertsSectionEnabled(false) with
            // cancelUpcoming() - this is that half.
            scheduler.cancelUpcoming()
            assertThat(scheduledAlarmCount()).isEqualTo(0)
        }
}
