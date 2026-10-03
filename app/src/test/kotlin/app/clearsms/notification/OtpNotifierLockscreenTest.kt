package app.clearsms.notification

import android.app.Notification
import android.content.Context
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.categorizer.SenderIdLookup
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.NotificationAction
import app.clearsms.domain.model.OtpDisplaySize
import app.clearsms.testing.FakeSettingsRepository
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

    /** Stub resolver: keeps the raw sender so assertions stay deterministic. */
    private val rawResolver =
        object : NotificationSenderResolver(
            context,
            app.clearsms.sms.ContactsSource(context),
            SenderIdLookup { null },
        ) {
            override fun resolve(sender: String) = NotificationSender(name = sender, monogram = "X")
        }

    private fun build(selected: Set<NotificationAction> = MessageNotifier.DEFAULT_SELECTED): Notification =
        OtpNotifier(
            context,
            rawResolver,
            SenderIconFactory(context),
            NotificationSectionGate(FakeSettingsRepository()),
            MutedSenderGate(FakeSettingsRepository()),
        ).build(message, "123456", OtpDisplaySize.DEFAULT, selected)

    private fun build(displaySize: OtpDisplaySize): Notification =
        OtpNotifier(
            context,
            rawResolver,
            SenderIconFactory(context),
            NotificationSectionGate(FakeSettingsRepository()),
            MutedSenderGate(FakeSettingsRepository()),
        ).build(message, "123456", displaySize, MessageNotifier.DEFAULT_SELECTED)

    /** Inflates the custom (collapsed) content view and returns the code TextView. */
    @Suppress("DEPRECATION")
    private fun codeView(notification: Notification): TextView {
        val view = notification.contentView.apply(context, FrameLayout(context))
        return view.findViewById(app.clearsms.R.id.otp_code)
    }

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

    @Test
    fun `tap carries a content intent into the conversation`() {
        // Regression guard (issue #8 family): every notifier must give the
        // notification body a tap target - an OTP card whose tap does nothing
        // reads as broken even though its Copy action works.
        assertThat(build().contentIntent).isNotNull()
    }

    @Test
    fun `display size changes the rendered code size - a title span would be stripped by the platform`() {
        // Regression guard: on API 24+ Notification.safeCharSequence() drops
        // RelativeSizeSpan/AbsoluteSizeSpan from title/text/bigText, so the
        // only way the setting can reach the shade is a sized TextView in a
        // DecoratedCustomViewStyle content view.
        val sizesPx = OtpDisplaySize.entries.map { codeView(build(it)).textSize }
        assertThat(sizesPx).isInStrictOrder()
        assertThat(build().bigContentView).isNotNull()
    }

    @Test
    fun `code is spaced digit by digit in both the custom view and the title`() {
        // Spaced digits are what TalkBack reads one at a time; the title stays
        // set for services and surfaces that ignore custom views.
        val notification = build(OtpDisplaySize.OPTION_5)
        assertThat(codeView(notification).text.toString()).isEqualTo("1 2 3 4 5 6")
        assertThat(notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
            .isEqualTo("1 2 3 4 5 6")
        // The clipboard payload is the unspaced code, carried by the Copy action's intent.
        assertThat(OtpNotifier.buildTitle("123456").toString().replace(" ", "")).isEqualTo("123456")
    }
}
