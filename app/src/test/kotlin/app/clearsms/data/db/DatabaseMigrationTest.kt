package app.clearsms.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Validates the auto migrations against the committed schema JSONs — a
 * destructive fallback would silently wipe user data on app update.
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ClearSmsDatabase::class.java,
        )

    @Test
    fun `migrate 2 to 3 keeps data and defaults enabled to true`() {
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL(
                """
                INSERT INTO rules (id, name, priority, matchJson, actionJson, isUserDefined, source, createdAt)
                VALUES ('generic-otp', 'OTP', 100, '{}', '{}', 0, 'builtin', 0)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO messages (threadId, sender, normalizedSender, body, timestamp,
                                      isRead, isArchived, category, isBlockedSender)
                VALUES (1, 'AX-TEST', 'TEST', 'hello', 1000, 0, 0, 'PERSONAL', 0)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true)

        db.query("SELECT id, enabled FROM rules").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("generic-otp")
            assertThat(cursor.getInt(1)).isEqualTo(1)
        }
        db.query("SELECT COUNT(*) FROM messages").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(1)
        }
    }

    @Test
    fun `migrate 1 to 3 chains both auto migrations`() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                """
                INSERT INTO rules (id, name, priority, matchJson, actionJson, isUserDefined, source, createdAt)
                VALUES ('r1', 'R1', 1, '{}', '{}', 1, 'user', 0)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true)

        db.query("SELECT enabled FROM rules WHERE id = 'r1'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(1)
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
