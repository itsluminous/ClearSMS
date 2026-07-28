package app.clearsms.data.repository

import app.clearsms.data.db.TransactionEntity
import app.clearsms.domain.model.MerchantCategory
import app.clearsms.domain.model.TransactionType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit coverage for [TransactionDeduplication] — the logic that stops one
 * payment becoming several TransactionEntity rows when a bank sends more than
 * one SMS alert for it, while never merging two genuinely distinct payments.
 */
class TransactionDeduplicationTest {
    private fun tx(
        id: Long = 0,
        amount: Double = 1000.0,
        type: TransactionType = TransactionType.DEBIT,
        merchant: String? = null,
        account: String = "8709",
        bank: String = "HDFC Bank",
        accountId: Long? = 1,
        timestamp: Long = 1_000_000L,
        balance: Double? = null,
        reference: String? = null,
    ) = TransactionEntity(
        id = id,
        amount = amount,
        type = type,
        merchantName = merchant,
        accountNumber = account,
        bankName = bank,
        accountId = accountId,
        timestamp = timestamp,
        balance = balance,
        referenceNumber = reference,
        category = MerchantCategory.OTHER,
        rawSmsId = id,
        note = null,
    )

    @Test
    fun `same reference on the same account is a duplicate even days apart`() {
        val a = tx(id = 1, reference = "UPI123456789", timestamp = 0)
        val b = tx(id = 2, reference = "upi123456789", timestamp = 5L * 86_400_000L)
        assertThat(TransactionDeduplication.isDuplicate(a, b)).isTrue()
    }

    @Test
    fun `different references same amount and day are NOT duplicates`() {
        val a = tx(id = 1, reference = "REF11111111", timestamp = 0)
        val b = tx(id = 2, reference = "REF22222222", timestamp = 30_000)
        assertThat(TransactionDeduplication.isDuplicate(a, b)).isFalse()
    }

    @Test
    fun `no-reference twins within the window are duplicates`() {
        val a = tx(id = 1, timestamp = 0)
        val b = tx(id = 2, timestamp = 40_000)
        assertThat(TransactionDeduplication.isDuplicate(a, b)).isTrue()
    }

    @Test
    fun `no-reference pair just outside the window is not a duplicate`() {
        val a = tx(id = 1, timestamp = 0)
        val b = tx(id = 2, timestamp = TransactionDeduplication.NEAR_DUPLICATE_WINDOW_MS + 1)
        assertThat(TransactionDeduplication.isDuplicate(a, b)).isFalse()
    }

    @Test
    fun `differing post-transaction balances veto a merge inside the window`() {
        val a = tx(id = 1, timestamp = 0, balance = 5000.0)
        val b = tx(id = 2, timestamp = 10_000, balance = 4000.0)
        assertThat(TransactionDeduplication.isDuplicate(a, b)).isFalse()
    }

    @Test
    fun `differing merchants veto a merge inside the window`() {
        val a = tx(id = 1, timestamp = 0, merchant = "Fund A")
        val b = tx(id = 2, timestamp = 10_000, merchant = "Fund B")
        assertThat(TransactionDeduplication.isDuplicate(a, b)).isFalse()
    }

    @Test
    fun `different accounts are never merged`() {
        val a = tx(id = 1, account = "8709", timestamp = 0)
        val b = tx(id = 2, account = "1234", timestamp = 10_000)
        assertThat(TransactionDeduplication.isDuplicate(a, b)).isFalse()
    }

    @Test
    fun `different types are never merged`() {
        val a = tx(id = 1, type = TransactionType.DEBIT, timestamp = 0)
        val b = tx(id = 2, type = TransactionType.CREDIT, timestamp = 10_000)
        assertThat(TransactionDeduplication.isDuplicate(a, b)).isFalse()
    }

    @Test
    fun `collapse keeps a real merchant and reference over blanks and preserves the note`() {
        val existing = tx(id = 1, merchant = null, reference = null).copy(note = "rent")
        val incoming = tx(id = 2, merchant = "NoBroker", reference = "REF99999999")
        val merged = TransactionDeduplication.collapse(existing, incoming)
        assertThat(merged.merchantName).isEqualTo("NoBroker")
        assertThat(merged.referenceNumber).isEqualTo("REF99999999")
        assertThat(merged.note).isEqualTo("rent")
    }

    @Test
    fun `short or digitless reference tokens are not usable identifiers`() {
        assertThat(TransactionDeduplication.normalizedReference("details")).isNull()
        assertThat(TransactionDeduplication.normalizedReference("  ")).isNull()
        assertThat(TransactionDeduplication.normalizedReference("UPI7654321")).isEqualTo("UPI7654321")
    }
}
