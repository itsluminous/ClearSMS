package app.clearsms.mms

import android.app.NotificationManager
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.MmsStatus
import app.clearsms.data.repository.MessageRepositoryImpl
import app.clearsms.data.rules.BundledRuleLoader
import app.clearsms.data.rules.RuleEngine
import app.clearsms.domain.categorizer.ContactLookup
import app.clearsms.domain.categorizer.MessageCategorizer
import app.clearsms.domain.categorizer.SenderIdLookup
import app.clearsms.notification.IncomingMessageRouter
import app.clearsms.notification.MessageNotifier
import app.clearsms.notification.NotificationSender
import app.clearsms.notification.NotificationSenderResolver
import app.clearsms.notification.OtpNotifier
import app.clearsms.notification.SenderIconFactory
import app.clearsms.notification.TransactionNotifier
import app.clearsms.testing.FakeSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The end-to-end incoming-MMS pipeline against a real in-memory database
 * and real notification router, with only the carrier transaction faked:
 * WAP push -> pending row + download; result -> parse/store/notify, or one
 * retry then a visible FAILED row that a tap flips back to PENDING.
 */
@RunWith(RobolectricTestRunner::class)
class MmsInboundTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var repository: MessageRepositoryImpl
    private lateinit var attachmentStore: AttachmentStore
    private lateinit var inbound: MmsInbound
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val json = Json { ignoreUnknownKeys = true }

    /** Records download requests instead of talking to the platform. */
    private class FakeDownloader : MmsDownloader {
        val calls = mutableListOf<Triple<Long, String, Int>>()

        override fun download(
            messageId: Long,
            contentLocation: String,
            attempt: Int,
        ) {
            calls += Triple(messageId, contentLocation, attempt)
        }
    }

    private val downloader = FakeDownloader()

    private object NoopStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }

    private val rawResolver =
        object : NotificationSenderResolver(
            context,
            app.clearsms.sms.ContactsSource(context),
            app.clearsms.data.senderid
                .SenderIdStore(context),
        ) {
            override fun resolve(sender: String) = NotificationSender(name = sender, monogram = "X")
        }

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
                        // Every sender is "a contact": personal senders
                        // notify, which lets the tests observe the shade.
                        contactLookup = ContactLookup { true },
                    ),
                bundledRuleLoader = BundledRuleLoader(context, db.ruleDao(), json, NoopStore),
                json = json,
            )
        attachmentStore = AttachmentStore(context)
        val iconFactory = SenderIconFactory(context)
        val router =
            IncomingMessageRouter(
                context = context,
                settingsRepository = FakeSettingsRepository(),
                otpNotifier = OtpNotifier(context, rawResolver, iconFactory),
                messageNotifier = MessageNotifier(context, rawResolver, iconFactory),
                transactionNotifier = TransactionNotifier(context, json, rawResolver, iconFactory),
                applicationScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
            )
        inbound = MmsInbound(repository, downloader, attachmentStore, router)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun shade() = shadowOf(context.getSystemService(NotificationManager::class.java))

    @Test
    fun `wap push stores a pending row and starts the download`() =
        runBlocking {
            val id = inbound.onNotification(MmsPduFixtures.notificationInd(), timestampMs = 1_000L)!!

            val row = db.messageDao().getById(id)!!
            assertThat(row.mmsStatus).isEqualTo(MmsStatus.PENDING)
            assertThat(row.sender).isEqualTo("+15551234567")
            assertThat(downloader.calls).containsExactly(Triple(id, "http://mmsc.example.com/msg/1", 0)).inOrder()
        }

    @Test
    fun `junk wap push is dropped without a row`() =
        runBlocking {
            assertThat(inbound.onNotification(byteArrayOf(1, 2, 3))).isNull()
            assertThat(db.messageDao().count()).isEqualTo(0)
            assertThat(downloader.calls).isEmpty()
        }

    @Test
    fun `first failure retries once, second failure leaves a visible FAILED row`() =
        runBlocking {
            val id = inbound.onNotification(MmsPduFixtures.notificationInd())!!

            inbound.onDownloadResult(id, succeeded = false, attempt = 0, contentLocation = { "http://mmsc.example.com/msg/1" })
            assertThat(downloader.calls.last()).isEqualTo(Triple(id, "http://mmsc.example.com/msg/1", 1))
            assertThat(db.messageDao().getById(id)!!.mmsStatus).isEqualTo(MmsStatus.PENDING)

            inbound.onDownloadResult(id, succeeded = false, attempt = 1, contentLocation = { "http://mmsc.example.com/msg/1" })
            assertThat(db.messageDao().getById(id)!!.mmsStatus).isEqualTo(MmsStatus.FAILED)
        }

    @Test
    fun `tapping a failed row retries the download`() =
        runBlocking {
            val id = inbound.onNotification(MmsPduFixtures.notificationInd())!!
            inbound.onDownloadResult(id, succeeded = false, attempt = 1, contentLocation = { null })
            downloader.calls.clear()

            inbound.retry(id)

            assertThat(db.messageDao().getById(id)!!.mmsStatus).isEqualTo(MmsStatus.PENDING)
            assertThat(downloader.calls).hasSize(1)
        }

    @Test
    fun `successful download stores body and image and notifies like an SMS`() =
        runBlocking {
            val id = inbound.onNotification(MmsPduFixtures.notificationInd())!!
            val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9, 9)
            attachmentStore.stagingFile(id).writeBytes(
                MmsPduFixtures.retrieveConf(
                    entries =
                        arrayOf(
                            MmsPduFixtures.smilPart(),
                            MmsPduFixtures.textPart("Look at this"),
                            MmsPduFixtures.jpegPart("photo.jpg", jpeg),
                        ),
                ),
            )

            inbound.onDownloadResult(id, succeeded = true, attempt = 0, contentLocation = { null })

            val row = db.messageDao().getById(id)!!
            assertThat(row.mmsStatus).isEqualTo(MmsStatus.DOWNLOADED)
            assertThat(row.body).isEqualTo("Look at this")
            assertThat(row.attachmentKinds).isEqualTo("IMAGE")
            val attachment = db.attachmentDao().forMessage(id).single()
            assertThat(attachment.mimeType).isEqualTo("image/jpeg")
            assertThat(attachment.sizeBytes).isEqualTo(jpeg.size.toLong())
            assertThat(attachmentStore.fileFor(id, attachment.fileName).readBytes()).isEqualTo(jpeg)
            assertThat(attachmentStore.stagingFile(id).exists()).isFalse()
            // PERSONAL (contact) message -> notified through the router.
            assertThat(shade().size()).isEqualTo(1)
        }

    @Test
    fun `blocked sender MMS completes silently`() =
        runBlocking {
            repository.insertIncoming("+15551234567", "earlier", 500L)
            repository.setBlocked("+15551234567", blocked = true)
            context.getSystemService(NotificationManager::class.java).cancelAll()
            val id = inbound.onNotification(MmsPduFixtures.notificationInd())!!
            attachmentStore
                .stagingFile(id)
                .writeBytes(MmsPduFixtures.retrieveConf(entries = arrayOf(MmsPduFixtures.textPart("blocked hello"))))

            inbound.onDownloadResult(id, succeeded = true, attempt = 0, contentLocation = { null })

            assertThat(db.messageDao().getById(id)!!.body).isEqualTo("blocked hello")
            assertThat(shade().size()).isEqualTo(0)
        }

    @Test
    fun `an unreadable staged pdu marks the row FAILED for retry`() =
        runBlocking {
            val id = inbound.onNotification(MmsPduFixtures.notificationInd())!!
            attachmentStore.stagingFile(id).writeBytes(byteArrayOf(0, 1, 2, 3))

            inbound.onDownloadResult(id, succeeded = true, attempt = 0, contentLocation = { null })

            assertThat(db.messageDao().getById(id)!!.mmsStatus).isEqualTo(MmsStatus.FAILED)
            assertThat(shade().size()).isEqualTo(0)
        }
}
