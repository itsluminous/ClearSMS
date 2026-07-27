package app.clearsms.notification

import android.app.PendingIntent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Every notification-action PendingIntent must be immutable; the RemoteInput
 * direct-reply intent is the single documented FLAG_MUTABLE exception (the
 * system must attach the typed text to it).
 */
class NotificationIntentsTest {
    @Test
    fun `default action flags are update-current plus immutable`() {
        assertThat(NotificationIntents.flags())
            .isEqualTo(PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    @Test
    fun `reply action flags are update-current plus mutable and never immutable`() {
        val flags = NotificationIntents.flags(mutable = true)
        assertThat(flags).isEqualTo(PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        assertThat(flags and PendingIntent.FLAG_IMMUTABLE).isEqualTo(0)
    }
}
