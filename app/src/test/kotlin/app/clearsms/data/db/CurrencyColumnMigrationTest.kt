package app.clearsms.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v22 → v23 adds `transactions.currency` and `accounts.currency` (issue #65).
 * Every pre-upgrade row is marked INR - it WAS parsed under the rupee
 * assumption - and no stored amount changes by so much as a rounding: a
 * migration that rewrote recorded figures would be worse than the bug.
 */
@RunWith(RobolectricTestRunner::class)
class CurrencyColumnMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ClearSmsDatabase::class.java,
        )

    @Test
    fun `migrate 22 to 23 marks existing rows INR and leaves every amount untouched`() {
        helper.createDatabase(TEST_DB, 22).apply {
            execSQL(
                """
                INSERT INTO accounts (id, accountNumber, bankName, type, lastKnownBalance, creditLimit, availableLimit, lastUpdated)
                VALUES (1, '8709', 'HDFC Bank', 'SAVINGS', 40194.56, NULL, NULL, 1000)
                """.trimIndent(),
            )
            // The reporter's shape: a Chilean "1.000" that the old parser stored
            // as 1.0. The migration must NOT "fix" it - it cannot know, and a
            // guess would rewrite history. The post-update re-sort re-derives
            // it from the SMS text with the currency-aware parser.
            execSQL(
                """
                INSERT INTO transactions (id, amount, type, merchantName, accountNumber, bankName, accountId, timestamp, balance, referenceNumber, category, rawSmsId, note)
                VALUES (1, 1.0, 'DEBIT', 'LIDER', '1234', '', NULL, 5000, NULL, NULL, 'OTHER', 11, NULL)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO transactions (id, amount, type, merchantName, accountNumber, bankName, accountId, timestamp, balance, referenceNumber, category, rawSmsId, note)
                VALUES (2, 1299.0, 'DEBIT', 'Swiggy', '8709', 'HDFC Bank', 1, 6000, 38895.56, 'UPI123456', 'FOOD', 12, 'lunch')
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 23, true)

        db.query("SELECT id, amount, currency, merchantName, balance, referenceNumber, note FROM transactions ORDER BY id").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(1)
            assertThat(cursor.getDouble(1)).isEqualTo(1.0)
            assertThat(cursor.getString(2)).isEqualTo("INR")
            assertThat(cursor.getString(3)).isEqualTo("LIDER")

            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getDouble(1)).isEqualTo(1299.0)
            assertThat(cursor.getString(2)).isEqualTo("INR")
            assertThat(cursor.getDouble(4)).isEqualTo(38895.56)
            assertThat(cursor.getString(5)).isEqualTo("UPI123456")
            assertThat(cursor.getString(6)).isEqualTo("lunch")
            assertThat(cursor.moveToNext()).isFalse()
        }
        db.query("SELECT accountNumber, currency, lastKnownBalance FROM accounts").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("8709")
            assertThat(cursor.getString(1)).isEqualTo("INR")
            assertThat(cursor.getDouble(2)).isEqualTo(40194.56)
        }

        // New rows carry their own currency after migration.
        db.execSQL(
            "INSERT INTO transactions (amount, currency, type, accountNumber, bankName, timestamp, category, rawSmsId) " +
                "VALUES (1000.0, 'CLP', 'DEBIT', '1234', '', 7000, 'OTHER', 13)",
        )
        db.query("SELECT currency FROM transactions WHERE rawSmsId = 13").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("CLP")
        }
    }

    private companion object {
        const val TEST_DB = "currency-column-migration-test.db"
    }
}
