package app.clearsms.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The v5→v6 migration adds the `messages_fts` index; existing messages must
 * be searchable immediately after upgrade (the index is back-filled, not
 * left to fill lazily as rows change).
 */
@RunWith(RobolectricTestRunner::class)
class FtsMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ClearSmsDatabase::class.java,
        )

    @Test
    fun `migrate 5 to 6 back-fills the search index for existing rows`() {
        helper.createDatabase(TEST_DB, 5).apply {
            execSQL(
                """
                INSERT INTO messages (id, threadId, sender, normalizedSender, body, timestamp,
                                      isRead, isArchived, category, isBlockedSender)
                VALUES (7, 1, 'AX-HDFCBK', 'HDFCBK', 'Salary credited to your account', 1000, 0, 0, 'IMPORTANT', 0)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true)

        db.query("SELECT rowid FROM messages_fts WHERE messages_fts MATCH 'salary*'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(7L)
        }
        // Sender tokens are indexed too.
        db.query("SELECT COUNT(*) FROM messages_fts WHERE messages_fts MATCH 'hdfc*'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(1)
        }
    }

    private companion object {
        const val TEST_DB = "fts-migration-test"
    }
}
