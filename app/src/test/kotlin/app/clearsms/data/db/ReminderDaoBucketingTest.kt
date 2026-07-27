package app.clearsms.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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

/** Deliveries bucket into upcoming/past by expected date exactly like bills. */
@RunWith(RobolectricTestRunner::class)
class ReminderDaoBucketingTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var dao: ReminderDao

    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 7, 27)
    private val cutoffMs = today.atStartOfDay(zone).toInstant().toEpochMilli()

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

    private fun delivery(
        id: Long,
        expected: LocalDate,
    ) = ReminderEntity(
        id = id,
        type = ReminderType.DELIVERY,
        dueDate = expected.atStartOfDay(zone).toInstant().toEpochMilli(),
        bankName = "Amazon",
        label = "403-000$id",
        rawSmsId = id * 10,
        createdAt = id,
    )

    @Test
    fun `past deliveries move to the past bucket, today and later stay upcoming`() {
        runBlocking {
            dao.insertAll(
                listOf(
                    delivery(id = 1, expected = today.minusDays(2)),
                    delivery(id = 2, expected = today),
                    delivery(id = 3, expected = today.plusDays(1)),
                ),
            )

            val upcoming = dao.observeUpcoming(cutoffMs).first()
            val past = dao.observePast(cutoffMs).first()

            assertThat(upcoming.map { it.id }).containsExactly(2L, 3L).inOrder()
            assertThat(past.map { it.id }).containsExactly(1L)
        }
    }
}
