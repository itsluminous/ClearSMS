package app.clearsms.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v3 -> v4 adds reminders.label and enforces the new invariant that a
 * reminder must carry a due date: undated rows (the junk the user saw in
 * the Alerts "Others" filter) are deleted, valid dated rows are preserved.
 */
@RunWith(RobolectricTestRunner::class)
class ReminderMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ClearSmsDatabase::class.java,
        )

    @Test
    fun `migrate 3 to 4 deletes undated reminders and keeps dated ones`() {
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL(
                """
                INSERT INTO reminders (type, dueDate, totalDue, minDue, accountLast4, bankName, rawSmsId, createdAt)
                VALUES ('OTHER', NULL, 4500.0, NULL, NULL, NULL, 1, 1000)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO reminders (type, dueDate, totalDue, minDue, accountLast4, bankName, rawSmsId, createdAt)
                VALUES ('CREDIT_CARD', 1790000000000, 15240.0, 762.0, '4400', 'HDFC Bank', 2, 2000)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true)

        db.query("SELECT type, dueDate, label FROM reminders").use { cursor ->
            // Only the dated credit-card reminder survives.
            assertThat(cursor.count).isEqualTo(1)
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("CREDIT_CARD")
            assertThat(cursor.getLong(1)).isEqualTo(1790000000000L)
            // The new label column exists and defaults to NULL.
            assertThat(cursor.isNull(2)).isTrue()
        }
    }

    @Test
    fun `migrate 3 to 4 leaves an all-dated table untouched`() {
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL(
                """
                INSERT INTO reminders (type, dueDate, totalDue, minDue, accountLast4, bankName, rawSmsId, createdAt)
                VALUES ('EMI', 1790000000000, NULL, NULL, NULL, 'Axis Bank', 1, 1000)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true)

        db.query("SELECT COUNT(*) FROM reminders").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(1)
        }
    }

    private companion object {
        const val TEST_DB = "reminder-migration-test.db"
    }
}
