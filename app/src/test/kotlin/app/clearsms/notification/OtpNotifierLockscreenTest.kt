package app.clearsms.notification

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.NotificationAction
import app.clearsms.domain.model.OtpDisplaySize
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The OTP digits are the notification title, so the notification must be
 * lockscreen-private and its public version must never contain the code.
 */
@RunWith(RobolectricTestRunner::class)
class OtpNotifierLockscreenTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val message =
        MessageEntity(
            id = 7L,
            threadId = 1L,
            sender = "AX-HDFCBK",
            normalizedSender = "HDFCBK",
            body = "123456 is your OTP for login. Do not share it.",
            timestamp = 1_000L,
            category = Category.OTP,
            extractedOtp = "123456",
        )

    private fun build(selected: Set<NotificationAction> = MessageNotifier.DEFAULT_SELECTED): Notification =
        OtpNotifier(context).build(message, "123456", OtpDisplaySize.DEFAULT, selected)

    @Test
    fun `notification is private with a public version`() {
        val notification = build()
        assertThat(notification.visibility).isEqualTo(NotificationCompat.VISIBILITY_PRIVATE)
        assertThat(notification.publicVersion).isNotNull()
    }

    @Test
    fun `public version text contains no digits`() {
        val public = build().publicVersion
        val title =
            public.extras
                .getCharSequence(NotificationCompat.EXTRA_TITLE)
                ?.toString()
                .orEmpty()
        val text =
            public.extras
                .getCharSequence(NotificationCompat.EXTRA_TEXT)
                ?.toString()
                .orEmpty()
        assertThat(title).isNotEmpty()
        assertThat(title.any { it.isDigit() }).isFalse()
        assertThat(text.any { it.isDigit() }).isFalse()
        assertThat(title).contains("AX-HDFCBK")
    }

    @Test
    fun `copy action is always present and honors the selection order`() {
        val actions = build(setOf(NotificationAction.SHARE_OTP)).actions.orEmpty()
        assertThat(actions.map { it.title.toString() }).containsExactly("Copy", "Share").inOrder()
    }
}
