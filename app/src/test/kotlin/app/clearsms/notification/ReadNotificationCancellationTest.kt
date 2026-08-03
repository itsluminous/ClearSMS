package app.clearsms.notification

import android.app.NotificationManager
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.repository.MessageRepositoryImpl
import app.clearsms.data.rules.BundledRuleLoader
import app.clearsms.data.rules.RuleEngine
import app.clearsms.domain.categorizer.ContactLookup
import app.clearsms.domain.categorizer.MessageCategorizer
import app.clearsms.domain.categorizer.SenderIdLookup
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.OtpDisplaySize
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * End-to-end regression tests for the "notification survives reading the
 * message in-app" defect: notifications are POSTED through the real
 * notifiers and cancellation flows through the real repository read/delete
 * paths into [NotificationDismisser], asserted against the shade
 * (Robolectric's shadow NotificationManager).
 *
 * Fixture: thread 1 (HDFCBK) holds message 1 (transaction) and message 2
 * (OTP); thread 2 (ICICIB) holds message 3 (transaction). Posting yields a
 * transaction notification per message, ONE group summary, an OTP
 * notification, and a message notification per thread.
 */
@RunWith(RobolectricTestRunner::class)
class ReadNotificationCancellationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: ClearSmsDatabase
    private lateinit var repository: MessageRepositoryImpl

    private val json = Json { ignoreUnknownKeys = true }
    private val iconFactory = SenderIconFactory(context)

    /** Stub resolver: raw sender name, so no contacts/directory access is needed. */
    private val rawResolver =
        object : NotificationSenderResolver(
            context,
            app.clearsms.sms.ContactsSource(context),
            app.clearsms.data.senderid
                .SenderIdStore(context),
        ) {
            override fun resolve(sender: String) = NotificationSender(name = sender, monogram = "X")
        }

    private val transactionNotifier = TransactionNotifier(context, json, rawResolver, iconFactory)
    private val messageNotifier = MessageNotifier(context, rawResolver, iconFactory)
    private val otpNotifier = OtpNotifier(context, rawResolver, iconFactory)

    private val txMessage1 =
        MessageEntity(
            id = 1L,
            threadId = 1L,
            sender = "AX-HDFCBK",
            normalizedSender = "HDFCBK",
            body = "Rs.500 debited from a/c **2863",
            timestamp = 1_000L,
            category = Category.IMPORTANT,
            extractedDataJson = """{"amount":"500.0","type":"debit","bank":"HDFC Bank"}""",
        )
    private val otpMessage2 =
        MessageEntity(
            id = 2L,
            threadId = 1L,
            sender = "AX-HDFCBK",
            normalizedSender = "HDFCBK",
            body = "123456 is your OTP",
            timestamp = 2_000L,
            category = Category.OTP,
            extractedOtp = "123456",
        )
    private val txMessage3 =
        MessageEntity(
            id = 3L,
            threadId = 2L,
            sender = "AX-ICICIB",
            normalizedSender = "ICICIB",
            body = "Rs.900 debited from a/c **1111",
            timestamp = 3_000L,
            category = Category.IMPORTANT,
            extractedDataJson = """{"amount":"900.0","type":"debit","bank":"ICICI Bank"}""",
        )

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository =
            MessageRepositoryImpl(
                database = db,
                categorizer =
                    MessageCategorizer(
                        ruleEngine = RuleEngine(),
                        senderIdLookup = SenderIdLookup { null },
                        contactLookup = ContactLookup { false },
                    ),
                bundledRuleLoader = BundledRuleLoader(context, db.ruleDao(), json, NoopDataStore),
                json = json,
                readNotificationCanceler = NotificationDismisser(context),
            )
        runBlocking {
            db.messageDao().insert(txMessage1)
            db.messageDao().insert(otpMessage2)
            db.messageDao().insert(txMessage3)
        }
        // Post everything the receiver would have posted for these messages.
        assertThat(transactionNotifier.notify(txMessage1, MessageNotifier.DEFAULT_SELECTED)).isTrue()
        assertThat(transactionNotifier.notify(txMessage3, MessageNotifier.DEFAULT_SELECTED)).isTrue()
        otpNotifier.notify(otpMessage2, "123456", OtpDisplaySize.DEFAULT, MessageNotifier.DEFAULT_SELECTED)
        messageNotifier.notify(txMessage1)
        messageNotifier.notify(txMessage3)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun activeIds(): Set<Int> =
        context
            .getSystemService(NotificationManager::class.java)
            .activeNotifications
            .map { it.id }
            .toSet()

    @Test
    fun `fixture posts every notification at its NotificationIds-derived id`() {
        // The symmetry half of the single-source contract: what the notifiers
        // POST is exactly what NotificationIds derives (the cancellation
        // tests below prove the cancel half against the same derivations).
        assertThat(activeIds()).containsExactly(
            NotificationIds.transaction(1L),
            NotificationIds.transaction(3L),
            NotificationIds.TRANSACTION_GROUP_SUMMARY,
            NotificationIds.otp(2L),
            NotificationIds.messageThread(1L),
            NotificationIds.messageThread(2L),
        )
    }

    @Test
    fun `opening a conversation cancels the thread's transaction, otp and message notifications`() {
        // ConversationViewModel's open path: mark the whole thread read.
        runBlocking { repository.setReadForThreads(listOf(1L), read = true) }
        val active = activeIds()
        assertThat(active).doesNotContain(NotificationIds.transaction(1L))
        assertThat(active).doesNotContain(NotificationIds.otp(2L))
        assertThat(active).doesNotContain(NotificationIds.messageThread(1L))
    }

    @Test
    fun `other threads' notifications survive reading one conversation`() {
        runBlocking { repository.setReadForThreads(listOf(1L), read = true) }
        val active = activeIds()
        assertThat(active).contains(NotificationIds.transaction(3L))
        assertThat(active).contains(NotificationIds.messageThread(2L))
    }

    @Test
    fun `group summary is kept while another transaction child remains`() {
        runBlocking { repository.setReadForThreads(listOf(1L), read = true) }
        assertThat(activeIds()).contains(NotificationIds.TRANSACTION_GROUP_SUMMARY)
    }

    @Test
    fun `group summary is cancelled when its last child goes`() {
        runBlocking {
            repository.setReadForThreads(listOf(1L), read = true)
            repository.setReadForThreads(listOf(2L), read = true)
        }
        assertThat(activeIds()).doesNotContain(NotificationIds.TRANSACTION_GROUP_SUMMARY)
    }

    @Test
    fun `mark-all-read cancels everything the app posted`() {
        runBlocking { repository.setReadForThreads(listOf(1L, 2L), read = true) }
        assertThat(activeIds()).isEmpty()
    }

    @Test
    fun `partial thread read keeps the unread messages' notifications`() {
        // Only the transaction message is read; the OTP in the same thread
        // stays unread, so its notification AND the thread's message
        // notification must survive.
        runBlocking { repository.markRead(1L, read = true) }
        val active = activeIds()
        assertThat(active).doesNotContain(NotificationIds.transaction(1L))
        assertThat(active).contains(NotificationIds.otp(2L))
        assertThat(active).contains(NotificationIds.messageThread(1L))
    }

    @Test
    fun `reading the rest of a partially read thread clears its remaining notifications`() {
        runBlocking {
            repository.markRead(1L, read = true)
            repository.markRead(2L, read = true)
        }
        val active = activeIds()
        assertThat(active).doesNotContain(NotificationIds.otp(2L))
        assertThat(active).doesNotContain(NotificationIds.messageThread(1L))
    }

    @Test
    fun `deleting an OTP message cancels its notification`() {
        // The OTP auto-delete path funnels through deleteMessages.
        runBlocking { repository.deleteMessages(listOf(2L)) }
        assertThat(activeIds()).doesNotContain(NotificationIds.otp(2L))
    }

    @Test
    fun `deleting threads cancels their notifications and reaps the summary`() {
        runBlocking { repository.deleteThreads(listOf(1L, 2L)) }
        assertThat(activeIds()).isEmpty()
    }

    @Test
    fun `marking unread cancels nothing`() {
        runBlocking { repository.markRead(1L, read = false) }
        assertThat(activeIds()).hasSize(6)
    }

    private object NoopDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }
}
