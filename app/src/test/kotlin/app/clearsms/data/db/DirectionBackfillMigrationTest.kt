package app.clearsms.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The v6→v7 migration adds `messages.isOutgoing` + `messages.deliveryStatus`
 * and reconciles existing rows against the system provider's sent box, so
 * the user's history renders with correct bubble sides after upgrade:
 * matched rows become outgoing, everything else defaults to incoming.
 */
@RunWith(RobolectricTestRunner::class)
class DirectionBackfillMigrationTest {
    private val sentRows = mutableListOf<SentSmsSource.SentSms>()

    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ClearSmsDatabase::class.java,
        )

    @Before
    fun setUp() {
        BackfillMessageDirections.sentSmsSource = FakeSentSmsSource(sentRows)
    }

    @After
    fun tearDown() {
        BackfillMessageDirections.sentSmsSource = null
    }

    private class FakeSentSmsSource(
        private val rows: List<SentSmsSource.SentSms>,
    ) : SentSmsSource {
        override fun sentMessages(): List<SentSmsSource.SentSms> = rows
    }

    private fun insertV6Message(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: Long,
        sender: String,
        body: String,
        timestamp: Long,
        systemSmsId: Long?,
    ) {
        db.execSQL(
            """
            INSERT INTO messages (id, threadId, sender, normalizedSender, body, timestamp,
                                  isRead, isArchived, category, isBlockedSender, systemSmsId)
            VALUES (?, 1, ?, ?, ?, ?, 1, 0, 'PERSONAL', 0, ?)
            """.trimIndent(),
            arrayOf(id, sender, sender, body, timestamp, systemSmsId),
        )
    }

    private fun directionAndStatus(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: Long,
    ): Pair<Int, String?> =
        db.query("SELECT isOutgoing, deliveryStatus FROM messages WHERE id = $id").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            cursor.getInt(0) to (if (cursor.isNull(1)) null else cursor.getString(1))
        }

    @Test
    fun `rows matched by systemSmsId become outgoing with their delivery state`() {
        sentRows +=
            listOf(
                SentSmsSource.SentSms(id = 501, address = "9876543210", body = "on my way", dateMs = 1_000, delivered = false),
                SentSmsSource.SentSms(id = 502, address = "9876543210", body = "reached", dateMs = 2_000, delivered = true),
            )
        helper.createDatabase(TEST_DB, 6).apply {
            insertV6Message(this, 1, "9876543210", "on my way", 1_000, systemSmsId = 501)
            insertV6Message(this, 2, "9876543210", "reached", 2_000, systemSmsId = 502)
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true)

        assertThat(directionAndStatus(db, 1)).isEqualTo(1 to "SENT")
        assertThat(directionAndStatus(db, 2)).isEqualTo(1 to "DELIVERED")
    }

    @Test
    fun `rows without systemSmsId match by sender timestamp and body`() {
        sentRows +=
            SentSmsSource.SentSms(id = 700, address = "9876543210", body = "sent from the app", dateMs = 5_000, delivered = false)
        helper.createDatabase(TEST_DB, 6).apply {
            // A reply persisted by the app before this release: no provider id stored.
            insertV6Message(this, 1, "9876543210", "sent from the app", 5_000, systemSmsId = null)
            // Same sender+body at a different time: NOT the sent message.
            insertV6Message(this, 2, "9876543210", "sent from the app", 6_000, systemSmsId = null)
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true)

        assertThat(directionAndStatus(db, 1)).isEqualTo(1 to "SENT")
        assertThat(directionAndStatus(db, 2)).isEqualTo(0 to null)
    }

    @Test
    fun `unmatched rows default to incoming with no status`() {
        sentRows +=
            SentSmsSource.SentSms(id = 900, address = "other", body = "different", dateMs = 1, delivered = false)
        helper.createDatabase(TEST_DB, 6).apply {
            insertV6Message(this, 1, "AX-HDFCBK", "Salary credited", 1_000, systemSmsId = 42)
            insertV6Message(this, 2, "9876543210", "hello there", 2_000, systemSmsId = null)
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true)

        assertThat(directionAndStatus(db, 1)).isEqualTo(0 to null)
        assertThat(directionAndStatus(db, 2)).isEqualTo(0 to null)
        db.query("SELECT COUNT(*) FROM messages WHERE isOutgoing = 1").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }
    }

    @Test
    fun `unreadable provider leaves every row incoming`() {
        // sentRows stays empty — the provider read degraded to nothing.
        helper.createDatabase(TEST_DB, 6).apply {
            insertV6Message(this, 1, "9876543210", "was actually sent", 1_000, systemSmsId = 77)
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true)

        assertThat(directionAndStatus(db, 1)).isEqualTo(0 to null)
    }

    private companion object {
        const val TEST_DB = "direction-backfill-migration-test"
    }
}
