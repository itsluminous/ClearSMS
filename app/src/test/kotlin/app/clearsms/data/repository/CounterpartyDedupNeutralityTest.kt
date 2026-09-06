package app.clearsms.data.repository

import app.clearsms.data.db.TransactionEntity
import app.clearsms.domain.model.MerchantCategory
import app.clearsms.domain.model.TransactionType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The parser's new WHY/WHO title fallbacks must not change WHICH
 * transactions dedup against each other. A counterparty account-tail title
 * ("A/c **5644") names the SAME payment's other leg - rows in that shape
 * previously carried no merchant at all - so the tier-2 merchant-differ
 * vetoes treat it as blank, keeping the UPI cross-bank echo collapse (and
 * every same-bank twin-alert collapse) exactly as before.
 *
 * All values synthetic.
 */
class CounterpartyDedupNeutralityTest {
    private fun tx(
        id: Long = 0,
        type: TransactionType = TransactionType.DEBIT,
        merchant: String? = null,
        account: String = "7311",
        bank: String = "HDFC Bank",
        accountId: Long? = null,
        timestamp: Long = 1_000_000L,
        reference: String? = null,
    ) = TransactionEntity(
        id = id,
        amount = 88_000.0,
        type = type,
        merchantName = merchant,
        accountNumber = account,
        bankName = bank,
        accountId = accountId,
        timestamp = timestamp,
        balance = null,
        referenceNumber = reference,
        category = MerchantCategory.OTHER,
        rawSmsId = id,
        note = null,
    )

    @Test
    fun `cross-bank upi echo still collapses when the bank leg now carries a counterparty tail`() {
        // Bank leg: "to a/c **5644" now titles the row "A/c **5644";
        // provider leg extracted the payee VPA. Same payment, no reference
        // on the provider leg - tier 2b must still merge, as it did when
        // the bank leg had no merchant at all.
        val bankLeg = tx(id = 1, merchant = "A/c **5644", bank = "HDFC Bank", timestamp = 0)
        val echoLeg = tx(id = 2, merchant = "shopmart.334@okybl", bank = "Yes Bank", timestamp = 60_000)
        assertThat(TransactionDeduplication.isCrossBankNearEcho(bankLeg, echoLeg)).isTrue()
        assertThat(TransactionDeduplication.isDuplicate(bankLeg, echoLeg)).isTrue()
    }

    @Test
    fun `same-bank twin alerts still collapse when one leg carries a counterparty tail`() {
        val alert = tx(id = 1, merchant = "A/c **5644", timestamp = 0)
        val statementLine = tx(id = 2, merchant = "shopmart.334@okybl", timestamp = 45_000)
        assertThat(TransactionDeduplication.isNearDuplicateAlert(alert, statementLine)).isTrue()
    }

    @Test
    fun `two REAL differing merchants still veto the twin-alert merge`() {
        // The neutralization is scoped to the counterparty-tail shape only:
        // genuinely different merchants remain two purchases.
        val a = tx(id = 1, merchant = "Fund Alpha SIP", timestamp = 0)
        val b = tx(id = 2, merchant = "Fund Beta SIP", timestamp = 45_000)
        assertThat(TransactionDeduplication.isNearDuplicateAlert(a, b)).isFalse()
    }

    @Test
    fun `reference echo never consulted merchants and still collapses`() {
        val bankLeg =
            tx(id = 1, merchant = "A/c **5644", bank = "HDFC Bank", reference = "728800112233", timestamp = 0)
        val echoLeg =
            tx(id = 2, merchant = "shopmart.334@okybl", bank = "Yes Bank", reference = "728800112233", timestamp = 120_000)
        assertThat(TransactionDeduplication.isCrossBankReferenceEcho(bankLeg, echoLeg)).isTrue()
    }
}
