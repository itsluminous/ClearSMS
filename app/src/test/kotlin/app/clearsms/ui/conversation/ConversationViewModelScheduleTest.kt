package app.clearsms.ui.conversation

import android.content.Context
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.DeliveryStatus
import app.clearsms.data.db.MessageDao
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.repository.UndoManager
import app.clearsms.data.senderid.SenderIdStore
import app.clearsms.domain.model.Category
import app.clearsms.mms.AttachmentStore
import app.clearsms.mms.MmsDownloader
import app.clearsms.mms.MmsGateway
import app.clearsms.mms.MmsInbound
import app.clearsms.mms.MmsSender
import app.clearsms.mms.OutgoingAttachmentStager
import app.clearsms.notification.IncomingMessageRouter
import app.clearsms.notification.MessageNotifier
import app.clearsms.notification.NotificationSenderResolver
import app.clearsms.notification.OtpNotifier
import app.clearsms.notification.SenderIconFactory
import app.clearsms.notification.TransactionNotifier
import app.clearsms.sms.ContactsSource
import app.clearsms.sms.SimChoiceStore
import app.clearsms.sms.SimInfo
import app.clearsms.sms.SmsSender
import app.clearsms.sms.SubscriptionSource
import app.clearsms.sms.TelephonyWriter
import app.clearsms.testing.FakeMessageRepository
import app.clearsms.testing.FakeSettingsRepository
import app.clearsms.testing.FakeSmsGateway
import app.clearsms.ui.common.ScheduleTipGate
import app.clearsms.ui.common.UiPrefs
import app.clearsms.work.MessageScheduler
import app.clearsms.work.ScheduledSendAlarms
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * Schedule parity with send on the CONVERSATION screen: confirming the
 * schedule picker consumes the compose text SYNCHRONOUSLY (the field and
 * the persisted draft clear together, exactly like a send - no leftover
 * draft next to the scheduled bubble), and a double-confirm carrying the
 * same stale body snapshot is dropped - exactly one SCHEDULED row.
 */
@RunWith(RobolectricTestRunner::class)
class ConversationViewModelScheduleTest {
    private lateinit var context: Context
    private lateinit var db: ClearSmsDatabase
    private lateinit var dao: MessageDao
    private lateinit var repository: FakeMessageRepository
    private var collectJob: Job? = null

    private val future = System.currentTimeMillis() + 300_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.messageDao()
        repository =
            object : FakeMessageRepository() {
                override suspend fun firstInThread(threadId: Long): MessageEntity? =
                    MessageEntity(
                        id = 1L,
                        threadId = threadId,
                        sender = "+15550001234",
                        normalizedSender = "5550001234",
                        body = "hi",
                        timestamp = 1L,
                        isRead = true,
                        category = Category.PERSONAL,
                    )
            }
    }

    @After
    fun tearDown() {
        collectJob?.cancel()
        db.close()
    }

    /** Polls [condition], draining the main looper (stateIn shares on Main). */
    private suspend fun awaitUntil(
        timeoutMs: Long = 5_000,
        condition: suspend () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) { "condition not met within ${timeoutMs}ms" }
            delay(10)
        }
    }

    private class FakeSubscriptionSource : SubscriptionSource {
        override fun activeSims(): List<SimInfo> = emptyList()

        override fun defaultSmsSubscriptionId(): Int? = null
    }

    private class FakeMmsGateway : MmsGateway {
        override fun sendMultimediaMessage(
            subscriptionId: Int?,
            pduFile: File,
            sentIntent: android.app.PendingIntent,
        ) = Unit
    }

    private fun viewModel(): ConversationViewModel {
        val uiPrefs =
            UiPrefs(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("ui_settings", ".preferences_pb")
                },
            )
        val smsSender =
            SmsSender(context, dao, TelephonyWriter(context), uiPrefs, Dispatchers.Unconfined, FakeSmsGateway())
        val mmsSender =
            MmsSender(
                context,
                dao,
                db.attachmentDao(),
                AttachmentStore(context),
                OutgoingAttachmentStager(context),
                FakeMmsGateway(),
                Dispatchers.Unconfined,
            )
        val json = Json { ignoreUnknownKeys = true }
        val resolver = NotificationSenderResolver(context, ContactsSource(context), SenderIdStore(context))
        val iconFactory = SenderIconFactory(context)
        val router =
            IncomingMessageRouter(
                context,
                FakeSettingsRepository(),
                OtpNotifier(context, resolver, iconFactory),
                MessageNotifier(context, resolver, iconFactory),
                TransactionNotifier(context, json, resolver, iconFactory),
                CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            )
        val mmsInbound =
            MmsInbound(
                repository,
                object : MmsDownloader {
                    override fun download(
                        messageId: Long,
                        contentLocation: String,
                        attempt: Int,
                    ) = Unit
                },
                AttachmentStore(context),
                router,
            )
        return ConversationViewModel(
            savedStateHandle = SavedStateHandle(mapOf("threadId" to THREAD_ID)),
            messageRepository = repository,
            undoManager = UndoManager(repository, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), { true }),
            senderIdStore = SenderIdStore(context),
            contactsSource = ContactsSource(context),
            smsSender = smsSender,
            mmsSender = mmsSender,
            attachmentStager = OutgoingAttachmentStager(context),
            sentMessageWatcher = SentMessageWatcher(dao, Dispatchers.Unconfined),
            subscriptionSource = FakeSubscriptionSource(),
            simChoiceStore =
                SimChoiceStore(
                    PreferenceDataStoreFactory.create {
                        File.createTempFile("sim_choice", ".preferences_pb")
                    },
                ),
            messageScheduler = MessageScheduler(dao, smsSender, ScheduledSendAlarms(context), Dispatchers.Unconfined),
            scheduleTipGate = ScheduleTipGate(FakeSettingsRepository()),
            attachmentDao = db.attachmentDao(),
            mmsInbound = mmsInbound,
            settings = FakeSettingsRepository(),
            json = json,
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    /** Subscribes uiState (WhileSubscribed) and waits for the address load. */
    private suspend fun awaitLoaded(vm: ConversationViewModel) {
        collectJob = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).launch { vm.uiState.collect {} }
        awaitUntil {
            vm.uiState.value.address
                .isNotBlank()
        }
    }

    @Test
    fun `confirming a schedule clears the compose field immediately and consumes the saved draft`() =
        runBlocking<Unit> {
            val vm = viewModel()
            awaitLoaded(vm)
            vm.setDraft("see you at nine")
            awaitUntil { repository.drafts[THREAD_ID] == "see you at nine" }

            vm.scheduleSend("see you at nine", future)

            // Synchronous consume: the field is empty the moment the picker
            // is confirmed, not when the row lands.
            assertThat(vm.draft.value).isEmpty()
            awaitUntil { dao.scheduledMessages().size == 1 }
            val row = dao.scheduledMessages().single()
            assertThat(row.deliveryStatus).isEqualTo(DeliveryStatus.SCHEDULED)
            assertThat(row.scheduledAt).isEqualTo(future)
            // The draft-consumption invariant: no leftover saved draft next
            // to the scheduled bubble.
            awaitUntil { !repository.drafts.containsKey(THREAD_ID) }
        }

    @Test
    fun `double-confirming a schedule creates exactly ONE scheduled row`() =
        runBlocking<Unit> {
            val vm = viewModel()
            awaitLoaded(vm)
            vm.setDraft("only one bubble")
            awaitUntil { repository.drafts[THREAD_ID] == "only one bubble" }

            // Both confirms carry the SAME body snapshot (a double-tap on
            // the picker's confirm button before recomposition).
            vm.scheduleSend("only one bubble", future)
            vm.scheduleSend("only one bubble", future)

            awaitUntil { dao.scheduledMessages().isNotEmpty() }
            delay(100)
            assertThat(dao.scheduledMessages()).hasSize(1)
        }

    private companion object {
        const val THREAD_ID = 7L
    }
}
