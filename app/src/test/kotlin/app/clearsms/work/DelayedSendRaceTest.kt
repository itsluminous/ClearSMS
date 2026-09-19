package app.clearsms.work

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.DeliveryStatus
import app.clearsms.data.db.MessageDao
import app.clearsms.data.db.MessageEntity
import app.clearsms.sms.SmsSender
import app.clearsms.sms.TelephonyWriter
import app.clearsms.testing.FakeSmsGateway
import app.clearsms.ui.common.UiPrefs
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The delayed-send (GitHub #40) cancel/fire race, made deterministic: a
 * [MessageDao] wrapper injects the alarm's fire into the exact window
 * between a cancel's status READ and its DELETE.
 *
 * The first test is the PROOF that this race is real and the test can see
 * it: the naive read-then-delete cancel (what [MessageScheduler.cancel]
 * used to do, reproduced verbatim) sends the SMS **and** deletes its row -
 * the user is told "cancelled" while the message went to the radio, and
 * the record of it vanishes. The remaining tests pin the fixed behaviour:
 * [MessageDao.deleteIfScheduled] is a compare-and-set against the fire's
 * own compare-and-set claim, so SQLite serializes the two writes and
 * exactly one wins - never both, never neither.
 */
@RunWith(RobolectricTestRunner::class)
class DelayedSendRaceTest {
    private lateinit var context: Context
    private lateinit var db: ClearSmsDatabase
    private lateinit var dao: MessageDao
    private lateinit var gateway: FakeSmsGateway
    private lateinit var smsSender: SmsSender
    private lateinit var scheduler: MessageScheduler

    private val future = System.currentTimeMillis() + 10_000L

    /**
     * Delegates everything to the real Room DAO but lets a test smuggle the
     * alarm's fire in AFTER a getById READ returns its (about to be stale)
     * snapshot - exactly the interleaving a real cancel/fire race produces.
     */
    private class RaceInjectingDao(
        private val delegate: MessageDao,
    ) : MessageDao by delegate {
        var afterGetById: (suspend () -> Unit)? = null
        var beforeDeleteIfScheduled: (suspend () -> Unit)? = null

        override suspend fun deleteIfScheduled(
            id: Long,
            scheduled: DeliveryStatus,
        ): Int {
            // The alarm's fire lands a hair before the cancel's DELETE
            // executes - the tightest interleaving the CAS must survive.
            val hook = beforeDeleteIfScheduled
            beforeDeleteIfScheduled = null
            hook?.invoke()
            return delegate.deleteIfScheduled(id, scheduled)
        }

        override suspend fun getById(id: Long): MessageEntity? {
            val snapshot = delegate.getById(id)
            // One-shot and cleared FIRST: the fire's own getById must not
            // re-trigger the hook (infinite recursion otherwise).
            val hook = afterGetById
            afterGetById = null
            hook?.invoke()
            return snapshot
        }
    }

    private lateinit var raceDao: RaceInjectingDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.messageDao()
        raceDao = RaceInjectingDao(dao)
        val uiPrefs =
            UiPrefs(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("ui_settings", ".preferences_pb")
                },
            )
        gateway = FakeSmsGateway()
        // The sender uses the RAW dao: the injected fire must behave like
        // the real alarm path, not re-enter the hook.
        smsSender = SmsSender(context, dao, TelephonyWriter(context), uiPrefs, Dispatchers.Unconfined, gateway)
        scheduler = MessageScheduler(raceDao, smsSender, ScheduledSendAlarms(context), uiPrefs, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun scheduleDelayed(): Long =
        runBlocking { scheduler.schedule("+15551234567", "typo!", subscriptionId = null, scheduledAtMs = future) }

    /** Fires the schedule exactly once, mid-cancel, via the dao hook. */
    private fun armFireInsideCancelWindow(id: Long) {
        raceDao.afterGetById = { smsSender.sendScheduled(id) }
    }

    @Test
    fun `PROOF - the naive read-then-delete cancel loses the race - message sent yet its record deleted`() =
        runBlocking<Unit> {
            val id = scheduleDelayed()
            armFireInsideCancelWindow(id)

            // The naive implementation, verbatim: read the status, then act
            // on the (by now stale) answer. The fire lands between the two.
            val message = raceDao.getById(id)
            if (message != null && message.deliveryStatus == DeliveryStatus.SCHEDULED) {
                raceDao.deleteById(id)
            }

            // BOTH outcomes happened - the exactly-once contract is broken:
            // the radio got the message...
            assertThat(gateway.sends).hasSize(1)
            // ...yet the row is gone, so the app claims a successful cancel
            // and the sent message leaves no trace. This test passing is
            // the proof the race test detects a naive implementation.
            assertThat(dao.getById(id)).isNull()
        }

    @Test
    fun `fire racing into the CAS cancel window - exactly one outcome, the send wins and is kept`() =
        runBlocking<Unit> {
            val id = scheduleDelayed()
            armFireInsideCancelWindow(id)

            val restoredBody = scheduler.cancelDelayed(id)

            // Cancel honestly reports it lost: nothing to restore.
            assertThat(restoredBody).isNull()
            // The message went out exactly once and its record survives.
            assertThat(gateway.sends).hasSize(1)
            assertThat(dao.getById(id)?.deliveryStatus).isEqualTo(DeliveryStatus.SENDING)
        }

    @Test
    fun `cancel before the delay expires - nothing sent, row gone, body returned for the composer`() =
        runBlocking<Unit> {
            val id = scheduleDelayed()

            val restoredBody = scheduler.cancelDelayed(id)

            assertThat(restoredBody).isEqualTo("typo!")
            assertThat(gateway.sends).isEmpty()
            assertThat(dao.getById(id)).isNull()
            // A late duplicate alarm finds nothing to claim: still no send.
            assertThat(smsSender.sendScheduled(id)).isFalse()
            assertThat(gateway.sends).isEmpty()
        }

    @Test
    fun `cancel after the message already fired - the sent row is left alone and no text is restored`() =
        runBlocking<Unit> {
            val id = scheduleDelayed()
            assertThat(smsSender.sendScheduled(id)).isTrue()

            val restoredBody = scheduler.cancelDelayed(id)

            assertThat(restoredBody).isNull()
            assertThat(dao.getById(id)?.deliveryStatus).isEqualTo(DeliveryStatus.SENDING)
            assertThat(gateway.sends).hasSize(1)
        }

    @Test
    fun `the fixed scheduler cancel is also CAS - a fire landing just before its DELETE keeps the sent row`() =
        runBlocking<Unit> {
            val id = scheduleDelayed()
            raceDao.beforeDeleteIfScheduled = { smsSender.sendScheduled(id) }

            scheduler.cancel(id)

            assertThat(gateway.sends).hasSize(1)
            assertThat(dao.getById(id)?.deliveryStatus).isEqualTo(DeliveryStatus.SENDING)
        }

    @Test
    fun `process death during the delay - rearmAll fires the overdue delayed message exactly once`() =
        runBlocking<Unit> {
            val id = scheduleDelayed()

            // A fresh scheduler instance over the same database is the
            // post-reboot world: the SCHEDULED row survived, the alarm did
            // not. rearmAll after the fire time dispatches immediately.
            val rebooted = MessageScheduler(dao, smsSender, ScheduledSendAlarms(context), uiPrefsOf(), Dispatchers.Unconfined)
            rebooted.rearmAll(nowMs = future + 1_000L)

            assertThat(gateway.sends).hasSize(1)
            assertThat(dao.getById(id)?.deliveryStatus).isEqualTo(DeliveryStatus.SENDING)
        }

    @Test
    fun `strip-accents fold still applies to a delayed send - at schedule time, so the bubble IS the wire body`() =
        runBlocking<Unit> {
            val foldingPrefs = uiPrefsOf()
            foldingPrefs.setStripAccents(true)
            val foldingScheduler =
                MessageScheduler(dao, smsSender, ScheduledSendAlarms(context), foldingPrefs, Dispatchers.Unconfined)
            // One non-GSM accent (č - unlike é, which is itself GSM-7 and
            // never folds) flips the whole body to UCS-2 (67-char segments);
            // folded it is one GSM-7 segment, so the fold saves and applies.
            val typed = "č" + "a".repeat(100)

            val id = foldingScheduler.schedule("+15551234567", typed, subscriptionId = null, scheduledAtMs = future)

            assertThat(dao.getById(id)?.body).isEqualTo("c" + "a".repeat(100))
        }

    private fun uiPrefsOf(): UiPrefs =
        UiPrefs(
            PreferenceDataStoreFactory.create {
                File.createTempFile("ui_settings_rearm", ".preferences_pb")
            },
        )
}
