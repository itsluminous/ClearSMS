package app.clearsms.work

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.TimeUnit

class ReminderAlarmSchedulerTest {
    private val day = TimeUnit.DAYS.toMillis(1)

    @Test
    fun `alarm fires one day before the due date`() {
        val now = 1_753_500_000_000L
        val dueDate = now + 5 * day
        assertThat(ReminderAlarmScheduler.triggerTimeFor(dueDate, now)).isEqualTo(dueDate - day)
    }

    @Test
    fun `no alarm when the due date is less than a day away`() {
        val now = 1_753_500_000_000L
        val dueDate = now + day / 2
        assertThat(ReminderAlarmScheduler.triggerTimeFor(dueDate, now)).isNull()
    }

    @Test
    fun `no alarm for past due dates`() {
        val now = 1_753_500_000_000L
        assertThat(ReminderAlarmScheduler.triggerTimeFor(now - day, now)).isNull()
    }
}
