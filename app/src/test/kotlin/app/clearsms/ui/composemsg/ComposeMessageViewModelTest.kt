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
import app.clearsms.sms.SimChoiceStore
import app.clearsms.sms.SimInfo
import app.clearsms.sms.SmsSender
import app.clearsms.sms.SubscriptionSource
import app.clearsms.sms.TelephonyWriter
import app.clearsms.testing.FakeSettingsRepository
import app.clearsms.testing.FakeSmsGateway
import app.clearsms.ui.common.UiPrefs
import app.clearsms.ui.conversation.SentMessageWatcher
import app.clearsms.work.MessageScheduler
import app.clearsms.work.ScheduledSendAlarms
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

    private fun viewModel(): ComposeMessageViewModel {
        val uiPrefs =
            UiPrefs(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("ui_settings", ".preferences_pb")
                },
            )
        val smsSender = SmsSender(context, dao, TelephonyWriter(context), uiPrefs, Dispatchers.Unconfined, FakeSmsGateway())
        return ComposeMessageViewModel(
            savedStateHandle = SavedStateHandle(),
            smsSender = smsSender,
            sentMessageWatcher = SentMessageWatcher(dao, Dispatchers.Unconfined),
            settings = FakeSettingsRepository(),
            contactSuggestions = ContactSuggestions(context),
            subscriptionSource = subscriptions,
            simChoiceStore = simChoiceStore,
            messageScheduler = MessageScheduler(dao, smsSender, ScheduledSendAlarms(context), Dispatchers.Unconfined),
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
            awaitUntil { vm.simState.value.label == "SIM 1" }
            assertThat(vm.simState.value.visible).isTrue()

            vm.onRecipientChange("+15551234567")
            // The remembered per-recipient choice (sub 20, slot 2) takes over.
            awaitUntil { vm.simState.value.label == "SIM 2" }
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
            awaitUntil { vm.simState.value.label == "SIM 1" }
            vm.cycleSim()

            awaitUntil { simChoiceStore.rememberedFor("+15559876543") == 20 }
            assertThat(vm.simState.value.label).isEqualTo("SIM 2")
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
            awaitUntil { vm.simState.value.label == "SIM 2" }
            vm.onBodyChange("hello")
            vm.send()

            awaitUntil { dao.threadIdFor("5551112222") != null }
            val rows = dao.observeThread(requireNotNull(dao.threadIdFor("5551112222"))).first()
            assertThat(rows.single().subscriptionId).isEqualTo(20)
        }
}
