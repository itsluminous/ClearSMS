package app.clearsms.ui.conversation

import android.content.Context
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.MessageDao
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.repository.SenderMuter
import app.clearsms.data.repository.UndoManager
import app.clearsms.domain.categorizer.SenderIdLookup
import app.clearsms.domain.model.Category
import app.clearsms.mms.MmsCapability
import app.clearsms.mms.AttachmentStore
import app.clearsms.mms.FakeCarrierMmsLimits
import app.clearsms.mms.MmsDownloader
import app.clearsms.mms.MmsGateway
import app.clearsms.mms.MmsInbound
import app.clearsms.mms.MmsSendConditionsProbe
import app.clearsms.mms.MmsSender
import app.clearsms.mms.OutgoingAttachmentStager
import app.clearsms.notification.IncomingMessageRouter
import app.clearsms.notification.MessageNotifier
import app.clearsms.notification.MutedSenderGate
import app.clearsms.notification.NotificationSectionGate
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
 * Live-update proof for the muted glyph in the conversation title bar: the
 * glyph draws from `uiState.muted`, and the overflow's Mute/Unmute goes
 * through the same [SenderMuter] that writes `settings.mutedSenders` - the
 * flow the view model combines into `uiState`. So a toggle taken INSIDE the
 * open conversation flips `uiState.muted` (and with it the glyph) with no
 * reload, and a mute made elsewhere (inbox, Settings) reaches the open
 * screen the same way. One shared [FakeSettingsRepository] plays the
 * DataStore both sides talk to. Fixtures are synthetic.
 */
@RunWith(RobolectricTestRunner::class)
class ConversationViewModelMuteIndicatorTest {
    private lateinit var context: Context
    private lateinit var db: ClearSmsDatabase
    private lateinit var dao: MessageDao
    private lateinit var repository: FakeMessageRepository
    private var collectJob: Job? = null

    /** The one settings store the view model reads and the muter writes. */
    private val settings = FakeSettingsRepository()

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

        override fun defaultDataSubscriptionId(): Int? = null
    }

    private class FakeMmsGateway : MmsGateway {
        override fun sendMultimediaMessage(
            messageId: Long,
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
                OutgoingAttachmentStager(context, FakeCarrierMmsLimits()),
                FakeMmsGateway(),
                MmsSendConditionsProbe(context, FakeSubscriptionSource()),
                FakeCarrierMmsLimits(),
                Dispatchers.Unconfined,
            )
        val json = Json { ignoreUnknownKeys = true }
        val resolver = NotificationSenderResolver(context, ContactsSource(context), SenderIdLookup { null })
        val iconFactory = SenderIconFactory(context)
        val router =
            IncomingMessageRouter(
                context,
                FakeSettingsRepository(),
                OtpNotifier(
                    context,
                    resolver,
                    iconFactory,
                    NotificationSectionGate(FakeSettingsRepository()),
                    MutedSenderGate(FakeSettingsRepository()),
                ),
                MessageNotifier(
                    context,
                    resolver,
                    iconFactory,
                    NotificationSectionGate(FakeSettingsRepository()),
                    MutedSenderGate(FakeSettingsRepository()),
                ),
                TransactionNotifier(
                    context,
                    json,
                    resolver,
                    iconFactory,
                    NotificationSectionGate(FakeSettingsRepository()),
                    MutedSenderGate(FakeSettingsRepository()),
                ),
                MutedSenderGate(FakeSettingsRepository()),
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
            senderIdLookup = SenderIdLookup { null },
            contactsSource = ContactsSource(context),
            smsSender = smsSender,
            mmsSender = mmsSender,
            attachmentStager = OutgoingAttachmentStager(context, FakeCarrierMmsLimits()),
            sentMessageWatcher = SentMessageWatcher(dao, Dispatchers.Unconfined),
            subscriptionSource = FakeSubscriptionSource(),
            simChoiceStore =
                SimChoiceStore(
                    PreferenceDataStoreFactory.create {
                        File.createTempFile("sim_choice", ".preferences_pb")
                    },
                ),
            mmsCapability = MmsCapability(context),
            messageScheduler = MessageScheduler(dao, smsSender, ScheduledSendAlarms(context), uiPrefs, Dispatchers.Unconfined),
            scheduleTipGate = ScheduleTipGate(FakeSettingsRepository()),
            attachmentDao = db.attachmentDao(),
            mmsInbound = mmsInbound,
            settings = settings,
            senderMuter = SenderMuter(settings),
            json = json,
            appContext = context,
            applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
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
    fun `toggling mute from the open conversation flips uiState muted live, both ways`() =
        runBlocking<Unit> {
            val vm = viewModel()
            awaitLoaded(vm)
            assertThat(vm.uiState.value.muted).isFalse()

            vm.toggleMute()
            awaitUntil { vm.uiState.value.muted }
            // The glyph's source of truth and the notifier's gate agree.
            assertThat(MutedSenderGate.matches(settings.mutedSenders.value, "+15550001234")).isTrue()

            vm.toggleMute()
            awaitUntil { !vm.uiState.value.muted }
            assertThat(settings.mutedSenders.value).isEmpty()
            // The rest of the title state is untouched by the toggle.
            assertThat(vm.uiState.value.address).isEqualTo("+15550001234")
            assertThat(vm.uiState.value.loaded).isTrue()
        }

    @Test
    fun `a mute made elsewhere reaches the open conversation without a reload`() =
        runBlocking<Unit> {
            val vm = viewModel()
            awaitLoaded(vm)
            assertThat(vm.uiState.value.muted).isFalse()

            // Inbox selection overflow or Settings: same SenderMuter, same store.
            SenderMuter(settings).mute("+1 555 000 1234")
            awaitUntil { vm.uiState.value.muted }

            SenderMuter(settings).unmute("15550001234")
            awaitUntil { !vm.uiState.value.muted }
        }

    companion object {
        const val THREAD_ID = 7L
    }
}
