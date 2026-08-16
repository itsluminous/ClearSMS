package app.clearsms.ui.composemsg

import android.app.AlarmManager
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.DeliveryStatus
import app.clearsms.data.db.MessageDao
import app.clearsms.mms.AttachmentStore
import app.clearsms.mms.MmsGateway
import app.clearsms.mms.MmsSender
import app.clearsms.mms.OutgoingAttachmentStager
import app.clearsms.sms.SimChoiceStore
import app.clearsms.sms.SimInfo
import app.clearsms.sms.SmsSender
import app.clearsms.sms.SubscriptionSource
import app.clearsms.sms.TelephonyWriter
import app.clearsms.testing.FakeSettingsRepository
import app.clearsms.testing.FakeSmsGateway
import app.clearsms.ui.common.ScheduleTipGate
import app.clearsms.ui.common.UiPrefs
import app.clearsms.work.MessageScheduler
import app.clearsms.work.ScheduledSendAlarms
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * New-conversation send parity: scheduling routes through the SAME
 * [MessageScheduler] the conversation screen uses (durable SCHEDULED row +
 * armed alarm, no forked send path), and the SIM default follows the same
 * per-recipient memory once a recipient is chosen.
 */
@RunWith(RobolectricTestRunner::class)
class ComposeMessageViewModelTest {
    private lateinit var context: Context
    private lateinit var db: ClearSmsDatabase
    private lateinit var dao: MessageDao
    private lateinit var simChoiceStore: SimChoiceStore
    private lateinit var subscriptions: FakeSubscriptionSource

    private val future = System.currentTimeMillis() + 300_000L

    private class FakeSubscriptionSource : SubscriptionSource {
        var sims: List<SimInfo> = emptyList()
        var defaultSub: Int? = null

        override fun activeSims(): List<SimInfo> = sims

        override fun defaultSmsSubscriptionId(): Int? = defaultSub
    }

    /** Polls [condition] (VM work crosses real DataStore/Room IO threads). */
    private suspend fun awaitUntil(
        timeoutMs: Long = 5_000,
        condition: suspend () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within ${timeoutMs}ms" }
            kotlinx.coroutines.delay(10)
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.messageDao()
        simChoiceStore =
            SimChoiceStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("sim_choice", ".preferences_pb")
                },
            )
        subscriptions = FakeSubscriptionSource()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private class FakeMmsGateway : MmsGateway {
        var sends = 0

        override fun sendMultimediaMessage(
            subscriptionId: Int?,
            pduFile: File,
            sentIntent: android.app.PendingIntent,
        ) {
            sends++
        }
    }

    private fun viewModel(
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        smsDao: MessageDao = dao,
    ): ComposeMessageViewModel {
        val uiPrefs =
            UiPrefs(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("ui_settings", ".preferences_pb")
                },
            )
        val smsSender = SmsSender(context, smsDao, TelephonyWriter(context), uiPrefs, Dispatchers.Unconfined, FakeSmsGateway())
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
        return ComposeMessageViewModel(
            savedStateHandle = savedStateHandle,
            smsSender = smsSender,
            mmsSender = mmsSender,
            attachmentStager = OutgoingAttachmentStager(context),
            messageDao = dao,
            settings = FakeSettingsRepository(),
            contactSuggestions = ContactSuggestions(context),
            subscriptionSource = subscriptions,
            simChoiceStore = simChoiceStore,
            messageScheduler = MessageScheduler(dao, smsSender, ScheduledSendAlarms(context), Dispatchers.Unconfined),
            scheduleTipGate = ScheduleTipGate(FakeSettingsRepository()),
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @Test
    fun `schedule creates the thread through the scheduler - durable row and armed alarm`() =
        runBlocking<Unit> {
            val vm = viewModel()
            vm.onRecipientChange("+15551234567")
            vm.onBodyChange("see you at nine")

            vm.schedule(future)

            awaitUntil { vm.uiState.value.scheduled }
            val rows = dao.observeThread(requireNotNull(dao.threadIdFor("5551234567"))).first()
            assertThat(rows).hasSize(1)
            assertThat(rows.single().deliveryStatus).isEqualTo(DeliveryStatus.SCHEDULED)
            assertThat(rows.single().scheduledAt).isEqualTo(future)
            val alarms = shadowOf(requireNotNull(context.getSystemService(AlarmManager::class.java)))
            assertThat(requireNotNull(alarms.peekNextScheduledAlarm()).triggerAtMs).isEqualTo(future)
            assertThat(vm.uiState.value.scheduled).isTrue()
        }

    @Test
    fun `sim default follows the per-recipient memory once a recipient is chosen`() =
        runBlocking<Unit> {
            subscriptions.sims =
                listOf(
                    SimInfo(subscriptionId = 10, slotIndex = 0, displayName = "Airtel"),
                    SimInfo(subscriptionId = 20, slotIndex = 1, displayName = "Jio"),
                )
            subscriptions.defaultSub = 10
            simChoiceStore.remember("+15551234567", 20)

            val vm = viewModel()
            // No recipient yet: the system default SIM (slot 1) is chosen.
            awaitUntil { vm.simState.value.slot == 1 }
            assertThat(vm.simState.value.visible).isTrue()

            vm.onRecipientChange("+15551234567")
            // The remembered per-recipient choice (sub 20, slot 2) takes over.
            awaitUntil { vm.simState.value.slot == 2 }
        }

    @Test
    fun `cycling the sim remembers the choice for the recipient`() =
        runBlocking<Unit> {
            subscriptions.sims =
                listOf(
                    SimInfo(subscriptionId = 10, slotIndex = 0, displayName = "Airtel"),
                    SimInfo(subscriptionId = 20, slotIndex = 1, displayName = "Jio"),
                )
            subscriptions.defaultSub = 10

            val vm = viewModel()
            vm.onRecipientChange("+15559876543")
            awaitUntil { vm.simState.value.slot == 1 }
            vm.cycleSim()

            awaitUntil { simChoiceStore.rememberedFor("+15559876543") == 20 }
            assertThat(vm.simState.value.slot).isEqualTo(2)
        }

    @Test
    fun `send carries the chosen sim like a conversation reply`() =
        runBlocking<Unit> {
            subscriptions.sims =
                listOf(
                    SimInfo(subscriptionId = 10, slotIndex = 0, displayName = "Airtel"),
                    SimInfo(subscriptionId = 20, slotIndex = 1, displayName = "Jio"),
                )
            subscriptions.defaultSub = 20

            val vm = viewModel()
            vm.onRecipientChange("+15551112222")
            awaitUntil { vm.simState.value.slot == 2 }
            vm.onBodyChange("hello")
            vm.send()

            awaitUntil { dao.threadIdFor("5551112222") != null }
            val rows = dao.observeThread(requireNotNull(dao.threadIdFor("5551112222"))).first()
            assertThat(rows.single().subscriptionId).isEqualTo(20)
        }
    // --- inbound image share ----------------------------------------------

    @Test
    fun `shared image is staged as a chip with text prefilled - and NEVER auto-sent`() =
        runBlocking<Unit> {
            val image = File(context.cacheDir, "shared.bin")
            image.writeBytes(ByteArray(256) { 5 })
            val vm =
                viewModel(
                    SavedStateHandle(
                        mapOf(
                            "body" to "look at this",
                            "imageUri" to
                                android.net.Uri
                                    .fromFile(image)
                                    .toString(),
                        ),
                    ),
                )

            awaitUntil { vm.attachments.value.isNotEmpty() }

            assertThat(vm.attachments.value).hasSize(1)
            assertThat(vm.uiState.value.body).isEqualTo("look at this")
            // Never auto-sent: no send attempt was made and nothing exists.
            assertThat(vm.uiState.value.sendStatus).isNull()
            assertThat(dao.getById(1L)).isNull()
        }

    @Test
    fun `send with a staged attachment goes out as MMS on one row - chips clear and it navigates`() =
        runBlocking<Unit> {
            val image = File(context.cacheDir, "shared2.bin")
            image.writeBytes(ByteArray(64) { 2 })
            val vm =
                viewModel(
                    SavedStateHandle(
                        mapOf(
                            "imageUri" to
                                android.net.Uri
                                    .fromFile(image)
                                    .toString(),
                        ),
                    ),
                )
            awaitUntil { vm.attachments.value.isNotEmpty() }
            vm.onRecipientChange("+15551230000")
            vm.onBodyChange("here")

            vm.send()
            val threadId = withTimeout(5_000) { vm.openThreadFlow.first() }

            val row = dao.getById(1L)
            assertThat(row).isNotNull()
            assertThat(row!!.threadId).isEqualTo(threadId)
            assertThat(row.attachmentKinds).isNotNull()
            assertThat(db.attachmentDao().forMessage(1L)).hasSize(1)
            // Chips AND text were consumed optimistically by the send.
            assertThat(vm.attachments.value).isEmpty()
            assertThat(vm.uiState.value.body).isEmpty()
        }

    // --- optimistic clear + navigate-into-thread on send --------------------

    @Test
    fun `send clears the compose box immediately and navigates into the created thread`() =
        runBlocking<Unit> {
            val vm = viewModel()
            vm.onRecipientChange("+15550001111")
            vm.onBodyChange("hello there")

            vm.send()

            // Optimistic consume: the field is empty the moment Send is
            // tapped, not when the radio reports back.
            assertThat(vm.uiState.value.body).isEmpty()
            val threadId = withTimeout(5_000) { vm.openThreadFlow.first() }
            assertThat(threadId).isEqualTo(dao.threadIdFor("5550001111"))
        }

    @Test
    fun `double-tapping send dispatches exactly ONE message`() =
        runBlocking<Unit> {
            val vm = viewModel()
            vm.onRecipientChange("+15550002222")
            vm.onBodyChange("only once")

            vm.send()
            vm.send()

            withTimeout(5_000) { vm.openThreadFlow.first() }
            val rows = dao.observeThread(requireNotNull(dao.threadIdFor("5550002222"))).first()
            assertThat(rows).hasSize(1)
        }

    @Test
    fun `dispatch failure restores the body and reports FAILED so Retry has text to retry`() =
        runBlocking<Unit> {
            // Persisting the outgoing row is the first step of a dispatch:
            // a throwing insert is the nothing-was-persisted failure shape.
            val failingDao =
                object : MessageDao by dao {
                    override suspend fun insert(message: app.clearsms.data.db.MessageEntity): Long =
                        throw IllegalStateException("disk full")
                }
            val vm = viewModel(smsDao = failingDao)
            vm.onRecipientChange("+15550003333")
            vm.onBodyChange("please arrive")

            vm.send()

            awaitUntil { vm.uiState.value.sendStatus == app.clearsms.ui.conversation.SendStatus.FAILED }
            assertThat(vm.uiState.value.body).isEqualTo("please arrive")
        }
}
