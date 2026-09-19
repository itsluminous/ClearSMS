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
import app.clearsms.domain.model.DelayedSendDelay
import app.clearsms.mms.AttachmentStore
import app.clearsms.mms.MmsDownloader
import app.clearsms.mms.MmsGateway
import app.clearsms.mms.MmsInbound
import app.clearsms.mms.MmsSender
import app.clearsms.mms.OutgoingAttachmentStager
import app.clearsms.notification.IncomingMessageRouter
import app.clearsms.notification.MessageNotifier
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
 * Delayed sending (GitHub #40) at the ViewModel seam:
 * - OFF (the default, per the maintainer's decision) keeps Send immediate;
 * - ON turns a plain Send into a durable SCHEDULED row at now+delay - the
 *   pending state - with a [SendEvent.Delayed] driving the cancel snackbar
 *   and NOTHING handed to the radio yet;
 * - Cancel before expiry restores the typed text into the composer;
 * - Cancel after the fire restores nothing and reports an honest
 *   "Message sent" instead ([SendEvent.Sent]).
 * The cancel/fire race itself is proven in DelayedSendRaceTest.
 */
@RunWith(RobolectricTestRunner::class)
class ConversationViewModelDelayedSendTest {
    private lateinit var context: Context
    private lateinit var db: ClearSmsDatabase
    private lateinit var dao: MessageDao
    private lateinit var repository: FakeMessageRepository
    private lateinit var settings: FakeSettingsRepository
    private lateinit var gateway: FakeSmsGateway
    private lateinit var scheduler: MessageScheduler
    private lateinit var smsSender: SmsSender

    // CopyOnWrite: appended by the collector coroutine while awaitUntil iterates.
    private val events = java.util.concurrent.CopyOnWriteArrayList<SendEvent>()
    private val jobs = mutableListOf<Job>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.messageDao()
        settings = FakeSettingsRepository()
        gateway = FakeSmsGateway()
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
        jobs.forEach(Job::cancel)
        db.close()
    }

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
        smsSender = SmsSender(context, dao, TelephonyWriter(context), uiPrefs, Dispatchers.Unconfined, gateway)
        scheduler = MessageScheduler(dao, smsSender, ScheduledSendAlarms(context), uiPrefs, Dispatchers.Unconfined)
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
                settings,
                OtpNotifier(context, resolver, iconFactory, NotificationSectionGate(settings)),
                MessageNotifier(context, resolver, iconFactory, NotificationSectionGate(settings)),
                TransactionNotifier(context, json, resolver, iconFactory, NotificationSectionGate(settings)),
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
        val vm =
            ConversationViewModel(
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
                messageScheduler = scheduler,
                scheduleTipGate = ScheduleTipGate(settings),
                attachmentDao = db.attachmentDao(),
                mmsInbound = mmsInbound,
                settings = settings,
                json = json,
                appContext = context,
                applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                ioDispatcher = Dispatchers.Unconfined,
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        jobs += scope.launch { vm.uiState.collect {} }
        jobs += scope.launch { vm.events.collect { events += it } }
        return vm
    }

    private suspend fun awaitLoaded(vm: ConversationViewModel) {
        awaitUntil {
            vm.uiState.value.address
                .isNotBlank()
        }
    }

    @Test
    fun `delayed sending OFF - the default - sends immediately with no pending row`() =
        runBlocking<Unit> {
            val vm = viewModel()
            awaitLoaded(vm)

            vm.send("right away")

            awaitUntil { gateway.sends.isNotEmpty() }
            assertThat(dao.scheduledMessages()).isEmpty()
            assertThat(gateway.lastSend?.parts).containsExactly("right away")
        }

    @Test
    fun `delayed sending ON - Send becomes a pending SCHEDULED row at now+delay, nothing on the radio yet`() =
        runBlocking<Unit> {
            settings.delayedSendEnabled.value = true
            settings.delayedSendDelay.value = DelayedSendDelay.SECONDS_10
            val vm = viewModel()
            awaitLoaded(vm)
            val before = System.currentTimeMillis()

            vm.setDraft("oops typo")
            vm.send("oops typo")

            awaitUntil { dao.scheduledMessages().size == 1 }
            val row = dao.scheduledMessages().single()
            // Pending state: SCHEDULED (never "Sent" before it is), durable
            // in Room, visible in-thread as the scheduled bubble.
            assertThat(row.deliveryStatus).isEqualTo(DeliveryStatus.SCHEDULED)
            assertThat(row.scheduledAt).isAtLeast(before + DelayedSendDelay.SECONDS_10.millis)
            assertThat(gateway.sends).isEmpty()
            // The composer cleared (the send consumed the draft)...
            assertThat(vm.draft.value).isEmpty()
            // ...and the cancel snackbar was asked for.
            awaitUntil { events.any { it is SendEvent.Delayed } }
            val pending = events.filterIsInstance<SendEvent.Delayed>().single()
            assertThat(pending.messageId).isEqualTo(row.id)
            assertThat(pending.delaySeconds).isEqualTo(10)
        }

    @Test
    fun `cancel before expiry - message never sent and the typed text is back in the composer`() =
        runBlocking<Unit> {
            settings.delayedSendEnabled.value = true
            val vm = viewModel()
            awaitLoaded(vm)
            vm.send("oops typo")
            awaitUntil { events.any { it is SendEvent.Delayed } }
            val pending = events.filterIsInstance<SendEvent.Delayed>().single()

            vm.cancelDelayedSend(pending.messageId)

            awaitUntil { vm.draft.value == "oops typo" }
            assertThat(gateway.sends).isEmpty()
            assertThat(dao.getById(pending.messageId)).isNull()
        }

    @Test
    fun `cancel restores WITHOUT clobbering text typed during the delay`() =
        runBlocking<Unit> {
            settings.delayedSendEnabled.value = true
            val vm = viewModel()
            awaitLoaded(vm)
            vm.send("first thought")
            awaitUntil { events.any { it is SendEvent.Delayed } }
            val pending = events.filterIsInstance<SendEvent.Delayed>().single()
            vm.setDraft("second thought")

            vm.cancelDelayedSend(pending.messageId)

            awaitUntil { vm.draft.value == "first thought\nsecond thought" }
        }

    @Test
    fun `cancel AFTER the fire - must NOT cancel a sent message, nothing restored, honest Sent event`() =
        runBlocking<Unit> {
            settings.delayedSendEnabled.value = true
            val vm = viewModel()
            awaitLoaded(vm)
            vm.send("too late")
            awaitUntil { events.any { it is SendEvent.Delayed } }
            val pending = events.filterIsInstance<SendEvent.Delayed>().single()
            // The delay expires: the alarm fires the message.
            assertThat(smsSender.sendScheduled(pending.messageId)).isTrue()

            vm.cancelDelayedSend(pending.messageId)

            // The honest outcome replaces the pending bar: "Message sent".
            awaitUntil { events.any { it is SendEvent.Sent } }
            assertThat(vm.draft.value).isEmpty()
            assertThat(gateway.sends).hasSize(1)
            assertThat(dao.getById(pending.messageId)?.deliveryStatus).isEqualTo(DeliveryStatus.SENDING)
        }

    private companion object {
        const val THREAD_ID = 7L
    }
}
