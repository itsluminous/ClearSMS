package app.clearsms.work

import app.clearsms.domain.model.SummaryFrequency
import app.clearsms.notification.SummaryNotifier
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek

class SummaryBuildingTest {
    private val moneyLine = "Spent %1$.2f, received %2$.2f"
    private val otpLine = "%1\$d OTPs"
    private val unreadLine = "%1\$d unread important"

    @Test
    fun `summary text contains all three lines in order`() {
        val text =
            SummaryNotifier.buildSummaryText(
                SummaryNotifier.Summary(
                    totalDebits = 1234.5,
                    totalCredits = 200.0,
                    otpCount = 3,
                    unreadImportant = 7,
                ),
                moneyLine = moneyLine,
                otpLine = otpLine,
                unreadLine = unreadLine,
            )
        val lines = text.lines()
        assertThat(lines).hasSize(3)
        assertThat(lines[0]).isEqualTo("Spent 1234.50, received 200.00")
        assertThat(lines[1]).isEqualTo("3 OTPs")
        assertThat(lines[2]).isEqualTo("7 unread important")
    }

    @Test
    fun `zero activity still renders a complete digest`() {
        val text =
            SummaryNotifier.buildSummaryText(
                SummaryNotifier.Summary(0.0, 0.0, 0, 0),
                moneyLine = moneyLine,
                otpLine = otpLine,
                unreadLine = unreadLine,
            )
        assertThat(text.lines()).hasSize(3)
        assertThat(text).contains("0 OTPs")
    }

    @Test
    fun `frequency OFF never runs`() {
        DayOfWeek.entries.forEach { day ->
            assertThat(DailySummaryWorker.shouldRun(SummaryFrequency.OFF, day)).isFalse()
        }
    }

    @Test
    fun `frequency DAILY runs every day`() {
        DayOfWeek.entries.forEach { day ->
            assertThat(DailySummaryWorker.shouldRun(SummaryFrequency.DAILY, day)).isTrue()
        }
    }

    @Test
    fun `frequency WEEKLY runs only on Monday`() {
        assertThat(DailySummaryWorker.shouldRun(SummaryFrequency.WEEKLY, DayOfWeek.MONDAY)).isTrue()
        assertThat(DailySummaryWorker.shouldRun(SummaryFrequency.WEEKLY, DayOfWeek.TUESDAY)).isFalse()
        assertThat(DailySummaryWorker.shouldRun(SummaryFrequency.WEEKLY, DayOfWeek.SUNDAY)).isFalse()
    }
}
