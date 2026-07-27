package app.clearsms.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.TimeUnit

class ClearOtpRangeTest {
    private val now = 1_753_500_000_000L

    @Test
    fun `all range has no lower bound`() {
        // Long.MAX_VALUE with the DAO's strict `timestamp < cutoff` matches
        // every real timestamp — "All" deletes regardless of age.
        assertThat(ClearOtpRange.ALL.cutoffMs(now)).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun `one day range cuts off 24 hours back`() {
        assertThat(ClearOtpRange.OLDER_THAN_1_DAY.cutoffMs(now))
            .isEqualTo(now - TimeUnit.DAYS.toMillis(1))
    }

    @Test
    fun `three day range cuts off three days back`() {
        assertThat(ClearOtpRange.OLDER_THAN_3_DAYS.cutoffMs(now))
            .isEqualTo(now - TimeUnit.DAYS.toMillis(3))
    }

    @Test
    fun `one week range cuts off seven days back`() {
        assertThat(ClearOtpRange.OLDER_THAN_1_WEEK.cutoffMs(now))
            .isEqualTo(now - TimeUnit.DAYS.toMillis(7))
    }

    @Test
    fun `two week range cuts off fourteen days back`() {
        assertThat(ClearOtpRange.OLDER_THAN_2_WEEKS.cutoffMs(now))
            .isEqualTo(now - TimeUnit.DAYS.toMillis(14))
    }

    @Test
    fun `one month range cuts off thirty days back`() {
        assertThat(ClearOtpRange.OLDER_THAN_1_MONTH.cutoffMs(now))
            .isEqualTo(now - TimeUnit.DAYS.toMillis(30))
    }

    @Test
    fun `every dialog option is covered exactly once`() {
        // The Settings dialog lists exactly these six options, in this order.
        assertThat(ClearOtpRange.entries.map { it.name })
            .containsExactly(
                "ALL",
                "OLDER_THAN_1_DAY",
                "OLDER_THAN_3_DAYS",
                "OLDER_THAN_1_WEEK",
                "OLDER_THAN_2_WEEKS",
                "OLDER_THAN_1_MONTH",
            ).inOrder()
    }
}
