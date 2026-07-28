package app.clearsms.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v7 → v8 adds `accounts.availableLimit` (nullable). The migration must
 * preserve every existing account field, leave the new column NULL for
 * pre-upgrade rows, and accept values afterwards.
 */
@RunWith(RobolectricTestRunner::class)
class AvailableLimitMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ClearSmsDatabase::class.java,
        )

    @Test
    fun `migrate 7 to 8 adds availableLimit and preserves account data`() {
        helper.createDatabase(TEST_DB, 7).apply {
            execSQL(
                """
                INSERT INTO accounts (accountNumber, bankName, type, lastKnownBalance, creditLimit, lastUpdated)
                VALUES ('4001', 'ICICI Bank', 'CREDIT_CARD', NULL, 300000.0, 1000)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO accounts (accountNumber, bankName, type, lastKnownBalance, creditLimit, lastUpdated)
                VALUES ('8709', 'HDFC Bank', 'SAVINGS', 40194.56, NULL, 2000)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true)

        db
            .query(
                "SELECT accountNumber, bankName, type, lastKnownBalance, creditLimit, availableLimit, lastUpdated " +
                    "FROM accounts ORDER BY accountNumber",
            ).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("4001")
                assertThat(cursor.getString(1)).isEqualTo("ICICI Bank")
                assertThat(cursor.getString(2)).isEqualTo("CREDIT_CARD")
                assertThat(cursor.isNull(3)).isTrue()
                assertThat(cursor.getDouble(4)).isEqualTo(300000.0)
                // Pre-upgrade rows start with no available limit.
                assertThat(cursor.isNull(5)).isTrue()
                assertThat(cursor.getLong(6)).isEqualTo(1000)

                assertThat(cursor.moveToNext()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("8709")
                assertThat(cursor.getDouble(3)).isEqualTo(40194.56)
                assertThat(cursor.isNull(5)).isTrue()
            }

        // The new column accepts values after migration.
        db.execSQL("UPDATE accounts SET availableLimit = 287185.45 WHERE accountNumber = '4001'")
        db.query("SELECT availableLimit FROM accounts WHERE accountNumber = '4001'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getDouble(0)).isEqualTo(287185.45)
        }
    }

    private companion object {
        const val TEST_DB = "available-limit-migration-test.db"
    }
}
