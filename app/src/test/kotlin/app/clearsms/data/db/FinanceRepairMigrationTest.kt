package app.clearsms.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v4 -> v5 repairs derived data:
 * - reminders are re-derived from messages (amounts + labels appear, the
 *   RD-as-EMI mis-typing is corrected, thank-you-for-payment junk is gone),
 * - accounts are canonicalized ("SBI" == "State Bank of India"), blank bank
 *   names are backfilled from the account's transactions, and duplicates
 *   merge into one row — while a last-4 shared by two REAL institutions
 *   stays two rows,
 * - transactions get the same names so they keep pointing at the survivor.
 */
@RunWith(RobolectricTestRunner::class)
class FinanceRepairMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ClearSmsDatabase::class.java,
        )

    @Test
    fun `migrate 4 to 5 rebuilds reminders with detail and drops settled junk`() {
        helper.createDatabase(TEST_DB, 4).apply {
            insertMessage(
                this,
                id = 1,
                sender = "VD-HDFCBK",
                body =
                    "RD Installment Due! Amount INR 12,345.00 Due on 05-AUG-26 " +
                        "HDFC Bank RD 987654 Check RD statement on the MobileBanking App",
            )
            insertMessage(
                this,
                id = 2,
                sender = "JK-TATALI",
                body =
                    "Thank You for an online payment of Rs.54321 on date 12/05/2026 with Transaction " +
                        "Reference Number 12345678901 towards your Tata AIA Life Insurance Policy.",
            )
            // Stale rows written by the old parser: the RD reminder was
            // mis-typed as EMI with no amount, the thank-you became INSURANCE.
            execSQL(
                """
                INSERT INTO reminders (type, dueDate, totalDue, minDue, accountLast4, bankName, label, rawSmsId, createdAt)
                VALUES ('EMI', 1786000000000, NULL, NULL, NULL, 'HDFC Bank', NULL, 1, 1000)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO reminders (type, dueDate, totalDue, minDue, accountLast4, bankName, label, rawSmsId, createdAt)
                VALUES ('INSURANCE', 1786000000000, NULL, NULL, NULL, NULL, NULL, 2, 2000)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true)

        db.query("SELECT type, totalDue, label, rawSmsId FROM reminders").use { cursor ->
            // Only the RD reminder survives — re-typed, with amount and label.
            assertThat(cursor.count).isEqualTo(1)
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("DEPOSIT")
            assertThat(cursor.getDouble(1)).isEqualTo(12345.0)
            assertThat(cursor.getString(2)).isEqualTo("RD xx7654")
            assertThat(cursor.getLong(3)).isEqualTo(1L)
        }
    }

    @Test
    fun `migrate 4 to 5 merges bank name variants and backfills blank banks`() {
        helper.createDatabase(TEST_DB, 4).apply {
            // Wallet transactions behind the blank 0310 row (a Citi card that
            // predates bank resolution).
            insertMessage(
                this,
                id = 10,
                sender = "TM-CITIBA",
                body = "Spent Rs 1,000 on your card xx0310 at STORE on 12-05-26.",
            )
            insertTransaction(this, id = 100, accountNumber = "0310", bankName = "", rawSmsId = 10)
            // "SBI" and "State Bank of India" rows for the same account.
            execSQL(
                """
                INSERT INTO accounts (accountNumber, bankName, type, lastKnownBalance, creditLimit, lastUpdated)
                VALUES ('0502', 'SBI', 'SAVINGS', 1200.0, NULL, 1000)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO accounts (accountNumber, bankName, type, lastKnownBalance, creditLimit, lastUpdated)
                VALUES ('0502', 'State Bank of India', 'SAVINGS', NULL, NULL, 2000)
                """.trimIndent(),
            )
            // Blank + named rows of the same Citi card.
            execSQL(
                """
                INSERT INTO accounts (accountNumber, bankName, type, lastKnownBalance, creditLimit, lastUpdated)
                VALUES ('0310', '', 'CREDIT_CARD', NULL, NULL, 1500)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO accounts (accountNumber, bankName, type, lastKnownBalance, creditLimit, lastUpdated)
                VALUES ('0310', 'Citi', 'CREDIT_CARD', 500.0, 90000.0, 1000)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true)

        db.query("SELECT bankName, lastKnownBalance FROM accounts WHERE accountNumber = '0502'").use { cursor ->
            // One canonical SBI row, newest kept, balance backfilled from the older row.
            assertThat(cursor.count).isEqualTo(1)
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("State Bank of India")
            assertThat(cursor.getDouble(1)).isEqualTo(1200.0)
        }
        db.query("SELECT bankName, creditLimit FROM accounts WHERE accountNumber = '0310'").use { cursor ->
            // The blank row resolved to Citi via its transaction sender and
            // merged into the named row; the credit limit survives.
            assertThat(cursor.count).isEqualTo(1)
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("Citi")
            assertThat(cursor.getDouble(1)).isEqualTo(90000.0)
        }
        db.query("SELECT bankName FROM transactions WHERE id = 100").use { cursor ->
            // The transaction points at the surviving Citi row, not at ''.
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("Citi")
        }
    }

    @Test
    fun `migrate 4 to 5 keeps a last-4 shared by two institutions as two accounts`() {
        helper.createDatabase(TEST_DB, 4).apply {
            // The same last-4 exists at a wallet AND at a bank — last-4 alone
            // must never be the merge key.
            insertMessage(
                this,
                id = 20,
                sender = "VD-Pluxee",
                body = "Rs. 350.00 spent from Pluxee Reimbursement wallet, card no.xx2703 at STORE. Avl bal Rs.10.",
            )
            insertTransaction(this, id = 200, accountNumber = "2703", bankName = "", rawSmsId = 20)
            execSQL(
                """
                INSERT INTO accounts (accountNumber, bankName, type, lastKnownBalance, creditLimit, lastUpdated)
                VALUES ('2703', '', 'CREDIT_CARD', NULL, NULL, 1000)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO accounts (accountNumber, bankName, type, lastKnownBalance, creditLimit, lastUpdated)
                VALUES ('2703', 'SBI', 'CREDIT_CARD', NULL, NULL, 2000)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true)

        db.query("SELECT bankName FROM accounts WHERE accountNumber = '2703' ORDER BY bankName").use { cursor ->
            assertThat(cursor.count).isEqualTo(2)
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("Pluxee")
            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("State Bank of India")
        }
    }

    private fun insertMessage(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: Long,
        sender: String,
        body: String,
    ) {
        db.execSQL(
            """
            INSERT INTO messages (id, threadId, sender, normalizedSender, body, timestamp, isRead, isArchived,
                                  category, subCategory, extractedOtp, extractedDataJson, isBlockedSender, systemSmsId)
            VALUES (?, 1, ?, ?, ?, 1000, 1, 0, 'IMPORTANT', NULL, NULL, NULL, 0, NULL)
            """.trimIndent(),
            arrayOf(id, sender, sender.uppercase(), body),
        )
    }

    private fun insertTransaction(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: Long,
        accountNumber: String,
        bankName: String,
        rawSmsId: Long,
    ) {
        db.execSQL(
            """
            INSERT INTO transactions (id, amount, type, merchantName, accountNumber, bankName, timestamp,
                                      balance, referenceNumber, category, rawSmsId, note)
            VALUES (?, 100.0, 'DEBIT', NULL, ?, ?, 1000, NULL, NULL, 'OTHER', ?, NULL)
            """.trimIndent(),
            arrayOf(id, accountNumber, bankName, rawSmsId),
        )
    }

    private companion object {
        const val TEST_DB = "finance-repair-migration-test.db"
    }
}
