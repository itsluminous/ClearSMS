package app.clearsms.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Promotional notifications are controlled solely from Android's notification
 * settings - there is no in-app toggle. The channel is created BLOCKED
 * (IMPORTANCE_NONE) so promos show nothing until the user switches the category
 * on, while still being visible there to switch on. Messages are posted to it
 * unconditionally; if they were gated in-app too, the system switch would
 * appear to do nothing.
 */
@RunWith(RobolectricTestRunner::class)
class PromotionsChannelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `promotions channel is registered`() {
        Channels.ensureCreated(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertThat(manager.getNotificationChannel(Channels.PROMOTIONS)).isNotNull()
    }

    @Test
    fun `promotions channel starts blocked so nothing shows until the user opts in`() {
        Channels.ensureCreated(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(Channels.PROMOTIONS)
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_NONE)
    }

    @Test
    fun `the v0_5_2 promotions channel is deleted so it cannot linger enabled`() {
        val manager = context.getSystemService(NotificationManager::class.java)
        // Simulate the old, enabled channel shipped by v0.5.2.
        manager.createNotificationChannel(
            NotificationChannel("promotions", "Promotions", NotificationManager.IMPORTANCE_LOW),
        )

        Channels.ensureCreated(context)

        assertThat(manager.getNotificationChannel("promotions")).isNull()
        assertThat(manager.getNotificationChannel(Channels.PROMOTIONS)).isNotNull()
    }

    @Test
    fun `promotions uses its own channel separate from messages`() {
        assertThat(Channels.PROMOTIONS).isNotEqualTo(Channels.MESSAGES)
    }
}
