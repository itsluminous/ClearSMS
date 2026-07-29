package app.clearsms.notification

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Promotional notifications are opt-in: the setting defaults to off (asserted
 * in SettingsRepositoryImplTest) and the channel they use is LOW importance, so
 * even when enabled they stay silent and never heads-up. The channel exists
 * unconditionally so users can find and block it under Android's own
 * notification categories.
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
    fun `promotions channel is low importance so it never interrupts`() {
        Channels.ensureCreated(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(Channels.PROMOTIONS)
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_LOW)
    }

    @Test
    fun `promotions uses its own channel separate from messages`() {
        assertThat(Channels.PROMOTIONS).isNotEqualTo(Channels.MESSAGES)
    }
}
