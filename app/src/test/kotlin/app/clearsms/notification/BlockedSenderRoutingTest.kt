package app.clearsms.notification

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.model.Category
import app.clearsms.testing.FakeSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Pinned behavior: a message from a blocked sender never notifies - neither
 * via the derived `isBlockedSender` flag nor via the born-deleted
 * (`deletedAt`) state the blocking ingest path produces. The control case
 * proves the same message WOULD notify unblocked, so a regression cannot
 * hide behind a silent test.
 */
@RunWith(RobolectricTestRunner::class)
class BlockedSenderRoutingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val json = Json { ignoreUnknownKeys = true }
    private val iconFactory = SenderIconFactory(context)

    private val rawResolver =
        object : NotificationSenderResolver(
            context,
            app.clearsms.sms.ContactsSource(context),
            app.clearsms.data.senderid
                .SenderIdStore(context),
        ) {
            override fun resolve(sender: String) = NotificationSender(name = sender, monogram = "X")
        }

    private val router =
        IncomingMessageRouter(
            context = context,
            settingsRepository = FakeSettingsRepository(),
            otpNotifier = OtpNotifier(context, rawResolver, iconFactory),
            messageNotifier = MessageNotifier(context, rawResolver, iconFactory),
            transactionNotifier = TransactionNotifier(context, json, rawResolver, iconFactory),
            applicationScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
        )

    private fun shade() = shadowOf(context.getSystemService(NotificationManager::class.java))

    private val personal =
        MessageEntity(
            id = 1L,
            threadId = 1L,
            sender = "9876543210",
            normalizedSender = "9876543210",
            body = "hey there",
            timestamp = 1_000L,
            category = Category.PERSONAL,
        )

    @Test
    fun `a blocked sender's message is silent, flag- and bin-wise`() =
        runBlocking {
            router.route(personal.copy(isBlockedSender = true))
            assertThat(shade().size()).isEqualTo(0)

            // The blocking ingest path produces born-deleted rows.
            router.route(personal.copy(isBlockedSender = true, deletedAt = 1_000L, isRead = true))
            assertThat(shade().size()).isEqualTo(0)
        }

    @Test
    fun `the same message notifies when not blocked (control)`() =
        runBlocking {
            router.route(personal)
            assertThat(shade().size()).isEqualTo(1)
        }
}
