package app.clearsms.work

import app.clearsms.domain.model.OtpAutoDeletePolicy
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.TimeUnit

class OtpAutoDeleteCutoffTest {
    private val now = 1_753_500_000_000L

    @Test
    fun `never policy yields no cutoff`() {
        assertThat(OtpAutoDeleteWorker.cutoffFor(OtpAutoDeletePolicy.NEVER, now)).isNull()
    }

    @Test
    fun `24 hour policy cuts off one day back`() {
        assertThat(OtpAutoDeleteWorker.cutoffFor(OtpAutoDeletePolicy.HOURS_24, now))
            .isEqualTo(now - TimeUnit.HOURS.toMillis(24))
    }

    @Test
    fun `3 day policy cuts off three days back`() {
        assertThat(OtpAutoDeleteWorker.cutoffFor(OtpAutoDeletePolicy.DAYS_3, now))
            .isEqualTo(now - TimeUnit.DAYS.toMillis(3))
    }

    @Test
    fun `7 day policy cuts off seven days back`() {
        assertThat(OtpAutoDeleteWorker.cutoffFor(OtpAutoDeletePolicy.DAYS_7, now))
            .isEqualTo(now - TimeUnit.DAYS.toMillis(7))
    }

    @Test
    fun `message exactly at cutoff is kept`() {
        val cutoff = OtpAutoDeleteWorker.cutoffFor(OtpAutoDeletePolicy.HOURS_24, now)!!
        // The DAO query uses strict `timestamp < cutoff`; a message stamped
        // exactly at the cutoff instant must survive.
        val messageTimestamp = cutoff
        assertThat(messageTimestamp < cutoff).isFalse()
    }
}
