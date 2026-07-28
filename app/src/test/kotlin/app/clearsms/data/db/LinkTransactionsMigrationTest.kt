package app.clearsms.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v8 → v9 ([ClearSmsDatabase.LinkTransactionsToAccounts]): adds
 * `transactions.accountId`, backfills it by (canonical bank, last-4), and
 * cleans up nameless (blank-bank) account rows. Seeded with the shape of
 * the real device snapshot: one last-4 shared by three banks.
 */
@RunWith(RobolectricTestRunner::class)
class LinkTransactionsMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ClearSmsDatabase::class.java,
        )

    private fun SupportSQLiteDatabase.insertAccount(
        id: Long,
        number: String,
        bank: String,
        type: String = "SAVINGS",
        balance: Double? = null,
        lastUpdated: Long = 1_000L,
    ) = execSQL(
        "INSERT INTO accounts (id, accountNumber, bankName, type, lastKnownBalance, creditLimit, availableLimit, lastUpdated) " +
            "VALUES (?, ?, ?, ?, ?, NULL, NULL, ?)",
        arrayOf(id, number, bank, type, balance, lastUpdated),
    )

    private fun SupportSQLiteDatabase.insertTransaction(
        id: Long,
        number: String,
        bank: String,
        note: String? = null,
    ) = execSQL(
        "INSERT INTO transactions (id, amount, type, merchantName, accountNumber, bankName, timestamp, balance, " +
            "referenceNumber, category, rawSmsId, note) VALUES (?, 10.0, 'DEBIT', NULL, ?, ?, ?, NULL, NULL, 'OTHER', ?, ?)",
        arrayOf(id, number, bank, id, id, note),
    )

    private fun ownerOf(
        db: SupportSQLiteDatabase,
        txId: Long,
    ): Long? =
        db.query("SELECT accountId FROM transactions WHERE id = $txId").use { cursor ->
            cursor.moveToFirst()
            if (cursor.isNull(0)) null else cursor.getLong(0)
        }

    @Test
    fun `backfill assigns each transaction to its own bank's account despite a shared last-4`() {
        helper.createDatabase(TEST_DB, 8).apply {
            insertAccount(1, "8709", "HDFC Bank")
            insertAccount(2, "8709", "State Bank of India")
            insertAccount(3, "8709", "Federal Bank")
            insertTransaction(10, "8709", "HDFC Bank")
            insertTransaction(11, "8709", "State Bank of India")
            insertTransaction(12, "8709", "Federal Bank")
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true)

        assertThat(ownerOf(db, 10)).isEqualTo(1)
        assertThat(ownerOf(db, 11)).isEqualTo(2)
        assertThat(ownerOf(db, 12)).isEqualTo(3)
    }

    @Test
    fun `backfill resolves bank name variants through canonicalization`() {
        helper.createDatabase(TEST_DB, 8).apply {
            insertAccount(1, "0502", "State Bank of India")
            // Written by an older parser before canonicalization.
            insertTransaction(10, "0502", "SBI")
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true)

        assertThat(ownerOf(db, 10)).isEqualTo(1)
    }

    @Test
    fun `bank-less transaction attaches only when exactly one named bank holds the tail`() {
        helper.createDatabase(TEST_DB, 8).apply {
            insertAccount(1, "4001", "ICICI Bank", type = "CREDIT_CARD")
            insertAccount(2, "8709", "HDFC Bank")
            insertAccount(3, "8709", "State Bank of India")
            // Sole owner of *4001 → attaches; *8709 is ambiguous → stays null.
            insertTransaction(10, "4001", "")
            insertTransaction(11, "8709", "")
            insertTransaction(12, "", "")
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true)

        assertThat(ownerOf(db, 10)).isEqualTo(1)
        assertThat(ownerOf(db, 11)).isNull()
        assertThat(ownerOf(db, 12)).isNull()
    }

    @Test
    fun `blank-bank account merges into the sole named account of the same tail and type`() {
        helper.createDatabase(TEST_DB, 8).apply {
            insertAccount(1, "5106", "Axis Bank", type = "CREDIT_CARD", balance = 100.0, lastUpdated = 5_000L)
            // Nameless duplicate created by the old ingestion path, NEWER and
            // carrying a balance the named row lacks in one field.
            insertAccount(2, "5106", "", type = "CREDIT_CARD", balance = 999.0, lastUpdated = 1_000L)
            insertTransaction(10, "5106", "")
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true)

        db.query("SELECT id, bankName, lastKnownBalance FROM accounts").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(1)
            assertThat(cursor.getString(1)).isEqualTo("Axis Bank")
            // The named row's own (newer) figure survives the merge.
            assertThat(cursor.getDouble(2)).isEqualTo(100.0)
            assertThat(cursor.moveToNext()).isFalse()
        }
        // Its transaction re-points to the surviving named account.
        assertThat(ownerOf(db, 10)).isEqualTo(1)
    }

    @Test
    fun `blank-bank account with no or several matching named accounts is deleted`() {
        helper.createDatabase(TEST_DB, 8).apply {
            insertAccount(1, "8709", "HDFC Bank")
            insertAccount(2, "8709", "State Bank of India")
            // Ambiguous: two named savings accounts share the tail.
            insertAccount(3, "8709", "")
            // No named account at all for this tail.
            insertAccount(4, "0709", "")
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true)

        db.query("SELECT id FROM accounts ORDER BY id").use { cursor ->
            val ids = mutableListOf<Long>()
            while (cursor.moveToNext()) ids += cursor.getLong(0)
            assertThat(ids).containsExactly(1L, 2L)
        }
    }

    @Test
    fun `migration preserves user notes on every transaction`() {
        helper.createDatabase(TEST_DB, 8).apply {
            insertAccount(1, "5106", "Axis Bank", type = "CREDIT_CARD")
            insertAccount(2, "5106", "", type = "CREDIT_CARD")
            insertTransaction(10, "5106", "Axis Bank", note = "lunch with team")
            insertTransaction(11, "5106", "", note = "reimbursable")
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true)

        db.query("SELECT id, note FROM transactions ORDER BY id").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(1)).isEqualTo("lunch with team")
            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getString(1)).isEqualTo("reimbursable")
        }
    }

    @Test
    fun `a card and a savings account sharing a tail keep their transactions apart`() {
        helper.createDatabase(TEST_DB, 8).apply {
            insertAccount(1, "2703", "State Bank of India", type = "CREDIT_CARD")
            insertAccount(2, "2703", "Pluxee", type = "WALLET")
            insertTransaction(10, "2703", "State Bank of India")
            insertTransaction(11, "2703", "Pluxee")
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true)

        assertThat(ownerOf(db, 10)).isEqualTo(1)
        assertThat(ownerOf(db, 11)).isEqualTo(2)
    }

    private companion object {
        const val TEST_DB = "link-transactions-migration-test.db"
    }
}
