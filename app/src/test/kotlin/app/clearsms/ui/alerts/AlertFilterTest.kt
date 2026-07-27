package app.clearsms.ui.alerts

import app.clearsms.data.db.ReminderEntity
import app.clearsms.domain.model.ReminderType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AlertFilterTest {
    private fun reminder(
        id: Long,
        type: ReminderType,
    ) = ReminderEntity(id = id, type = type, dueDate = 1_000L, rawSmsId = id, createdAt = id)

    @Test
    fun `every reminder type maps to exactly one pill - no residual others bucket`() {
        val pills = AlertFilter.entries.filter { it != AlertFilter.ALL }
        ReminderType.entries.forEach { type ->
            val matching = pills.filter { it.matches(type) }
            assertThat(matching).hasSize(1)
        }
    }

    @Test
    fun `bill pill covers reminders typed OTHER`() {
        assertThat(AlertFilter.BILL.matches(ReminderType.OTHER)).isTrue()
        // ... and nothing else.
        ReminderType.entries.filter { it != ReminderType.OTHER }.forEach { type ->
            assertThat(AlertFilter.BILL.matches(type)).isFalse()
        }
    }

    @Test
    fun `each dedicated pill matches only its own type`() {
        val expected =
            mapOf(
                AlertFilter.CREDIT_CARDS to ReminderType.CREDIT_CARD,
                AlertFilter.EMI to ReminderType.EMI,
                AlertFilter.INSURANCE to ReminderType.INSURANCE,
                AlertFilter.BILL to ReminderType.OTHER,
                AlertFilter.SUBSCRIPTION to ReminderType.SUBSCRIPTION,
                AlertFilter.DEPOSIT to ReminderType.DEPOSIT,
                AlertFilter.DELIVERY to ReminderType.DELIVERY,
            )
        expected.forEach { (pill, type) ->
            ReminderType.entries.forEach { candidate ->
                assertThat(pill.matches(candidate)).isEqualTo(candidate == type)
            }
        }
    }

    @Test
    fun `all pill matches every type`() {
        ReminderType.entries.forEach { type ->
            assertThat(AlertFilter.ALL.matches(type)).isTrue()
        }
    }

    @Test
    fun `pill row order is all, credit cards, emi, insurance, bill, subscription, deposit, delivery`() {
        assertThat(AlertFilter.entries)
            .containsExactly(
                AlertFilter.ALL,
                AlertFilter.CREDIT_CARDS,
                AlertFilter.EMI,
                AlertFilter.INSURANCE,
                AlertFilter.BILL,
                AlertFilter.SUBSCRIPTION,
                AlertFilter.DEPOSIT,
                AlertFilter.DELIVERY,
            ).inOrder()
    }

    @Test
    fun `counts cover upcoming and past combined, zero for empty pills`() {
        val upcoming =
            listOf(
                reminder(1, ReminderType.CREDIT_CARD),
                reminder(2, ReminderType.INSURANCE),
                reminder(3, ReminderType.OTHER),
            )
        val past =
            listOf(
                reminder(4, ReminderType.CREDIT_CARD),
                reminder(5, ReminderType.OTHER),
                reminder(6, ReminderType.DELIVERY),
            )

        val counts = AlertFilter.counts(upcoming, past)

        assertThat(counts[AlertFilter.ALL]).isEqualTo(6)
        assertThat(counts[AlertFilter.CREDIT_CARDS]).isEqualTo(2)
        assertThat(counts[AlertFilter.INSURANCE]).isEqualTo(1)
        assertThat(counts[AlertFilter.BILL]).isEqualTo(2)
        assertThat(counts[AlertFilter.DELIVERY]).isEqualTo(1)
        assertThat(counts[AlertFilter.EMI]).isEqualTo(0)
        assertThat(counts[AlertFilter.SUBSCRIPTION]).isEqualTo(0)
        assertThat(counts[AlertFilter.DEPOSIT]).isEqualTo(0)
    }
}
