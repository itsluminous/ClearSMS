package app.clearsms.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.repository.ReminderBucketing
import app.clearsms.domain.model.ReminderType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** DAO-level dismissal flags, alarm-feed filtering, and the Older retention purge. */
@RunWith(RobolectricTestRunner::class)
class ReminderDaoBucketingTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var dao: ReminderDao

    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 8, 12)
    private val nowMs = today.atStartOfDay(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.reminderDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun daysAgo(days: Long): Long = nowMs - TimeUnit.DAYS.toMillis(days)

    private fun delivery(
        id: Long,
        expected: LocalDate?,
        createdAt: Long = id,
        dismissedAt: Long? = null,
    ) = ReminderEntity(
        id = id,
        type = ReminderType.DELIVERY,
        dueDate = expected?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
        bankName = "Amazon",
        label = "403-000$id",
        rawSmsId = id * 10,
        createdAt = createdAt,
        dismissedAt = dismissedAt,
    )

    private fun bill(
        id: Long,
        dueDate: Long?,
        createdAt: Long,
        dismissedAt: Long? = null,
    ) = ReminderEntity(
        id = id,
        type = ReminderType.CREDIT_CARD,
        dueDate = dueDate,
        accountLast4 = "123$id",
        bankName = "IDFC FIRST",
        rawSmsId = id * 10,
        createdAt = createdAt,
        dismissedAt = dismissedAt,
    )

    @Test
    fun `dismiss is a flag not a delete and restore clears it`() {
        runBlocking {
            dao.insert(bill(1, dueDate = nowMs + 86_400_000L, createdAt = daysAgo(1)))

            dao.setDismissed(listOf(1L), dismissedAt = nowMs)
            assertThat(dao.getAll().single().dismissedAt).isEqualTo(nowMs)

            dao.setDismissed(listOf(1L), dismissedAt = null)
            assertThat(dao.getAll().single().dismissedAt).isNull()
        }
    }

    @Test
    fun `alarm feed excludes dismissed and undated reminders`() {
        runBlocking {
            dao.insertAll(
                listOf(
                    delivery(id = 1, expected = today.plusDays(1)),
                    delivery(id = 2, expected = today.plusDays(1), dismissedAt = nowMs),
                    delivery(id = 3, expected = null),
                ),
            )

            assertThat(dao.observeUpcoming(nowMs).first().map { it.id }).containsExactly(1L)
        }
    }

    @Test
    fun `purge drops only rows past retention keeping active and recent older`() {
        runBlocking {
            val retention = ReminderBucketing.retentionMs()
            dao.insertAll(
                listOf(
                    // Dismissed 91 days ago: purged. Dismissed yesterday: kept.
                    bill(1, dueDate = null, createdAt = daysAgo(100), dismissedAt = daysAgo(91)),
                    bill(2, dueDate = null, createdAt = daysAgo(100), dismissedAt = daysAgo(1)),
                    // Expired 91 days ago: purged. Expired yesterday: kept in Older.
                    bill(3, dueDate = daysAgo(91), createdAt = daysAgo(120)),
                    bill(4, dueDate = daysAgo(1), createdAt = daysAgo(30)),
                    // Active dated bill: never purged.
                    bill(5, dueDate = nowMs + 86_400_000L, createdAt = daysAgo(1)),
                    // Undated delivery: purged 90d after its 14-day window ends.
                    delivery(6, expected = null, createdAt = daysAgo(105)),
                    delivery(7, expected = null, createdAt = daysAgo(103)),
                ),
            )

            val purged =
                dao.purgeExpired(
                    dismissedCutoffMs = nowMs - retention,
                    dueCutoffMs = nowMs - retention,
                    deliveryCreatedCutoffMs = nowMs - retention - TimeUnit.DAYS.toMillis(14),
                    billCreatedCutoffMs = nowMs - retention - TimeUnit.DAYS.toMillis(30),
                )

            assertThat(purged).isEqualTo(3)
            assertThat(dao.getAll().map { it.id }).containsExactly(2L, 4L, 5L, 7L)
        }
    }
}
