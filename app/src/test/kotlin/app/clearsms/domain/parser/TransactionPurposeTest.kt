package app.clearsms.domain.parser

import app.clearsms.domain.model.TransactionType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The transaction row's WHY/WHO title fallback: when a body carries no
 * merchant, the title shows the stated PURPOSE ("for NEFT transaction",
 * a "for <narration>" descriptor); failing that, the COUNTERPARTY - for a
 * DEBIT the receiver ("to a/c <tail>"), for a CREDIT the sender ("from VPA
 * <handle>" / "from a/c <tail>"). The user's OWN account (the "from a/c" of
 * a debit, the "to a/c" of a credit) must NEVER surface as the counterparty,
 * and when neither purpose nor counterparty exists the title stays null -
 * exactly what was shown before, never a placeholder.
 *
 * All senders/tails/VPAs/refs/names are synthetic.
 */
class TransactionPurposeTest {
    private val parser = TransactionParser()

    @Test
    fun `neft debit shows the for-clause purpose, stopped before the via-channel`() {
        val result =
            parser.parse(
                "VM-HDFCBK-S",
                "Amt Deducted! Rs.41,000.00 from your HDFC Bank A/c XX4522 for NEFT transaction via HDFC Bank Online Banking",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(TransactionType.DEBIT)
        assertThat(result.merchantName).isEqualTo("NEFT transaction")
        assertThat(result.accountLast4).isEqualTo("4522")
    }

    @Test
    fun `upi debit with no purpose shows the RECEIVING account, never the user's own`() {
        val result =
            parser.parse(
                "VM-HDFCBK-S",
                "HDFC Bank:Rs. 88000.00 debited from a/c *7311 on 27/08/26 to a/c **5644 (UPI Ref No. 728800112233)",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(TransactionType.DEBIT)
        // The receiver's tail - NOT 7311, which is the user's own account.
        assertThat(result.merchantName).isEqualTo("A/c **5644")
        assertThat(result.accountLast4).isEqualTo("7311")
        assertThat(result.referenceNumber).isEqualTo("728800112233")
    }

    @Test
    fun `tpt deposit narration is trimmed to the human label - reference and rail code dropped`() {
        val result =
            parser.parse(
                "VM-HDFCBK-S",
                "Update! INR 27,410.00 deposited in HDFC Bank A/c XX6208 on 05-SEP-26 " +
                    "for XXXXXXXXXX2751-TPT-MonthlyRentNBill-ROHAN VERMA.Avl bal INR 5,120.40",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(TransactionType.CREDIT)
        // The masked reference and the TPT rail code say HOW, not WHY: the
        // payer-typed label plus the payer name is the readable purpose.
        assertThat(result.merchantName).isEqualTo("MonthlyRentNBill-ROHAN VERMA")
        assertThat(result.balance).isEqualTo(5120.40)
    }

    @Test
    fun `vpa credit with no purpose shows the SENDING vpa`() {
        val result =
            parser.parse(
                "VM-HDFCBK-S",
                "Credit Alert! Rs.15400.00 credited to HDFC Bank A/c XX9042 on 31-08-26 from VPA kiranblr@okaxis",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(TransactionType.CREDIT)
        assertThat(result.merchantName).isEqualTo("kiranblr@okaxis")
        // The user's OWN receiving account keys the account, not the title.
        assertThat(result.accountLast4).isEqualTo("9042")
    }

    @Test
    fun `card txn at-vpa merchant still wins over any fallback`() {
        val result =
            parser.parse(
                "VM-HDFCBK-S",
                "Txn Rs.649.00 On HDFC Bank Card 5566 At sampoornam.90123@okicici by UPI 118822334455 On 05-09",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(TransactionType.DEBIT)
        assertThat(result.merchantName).isEqualTo("sampoornam.90123@okicici")
    }

    @Test
    fun `credit naming both accounts shows the SENDER account, never the user's own`() {
        val result =
            parser.parse(
                "VM-HDFCBK-S",
                "Rs.1,200.00 credited to your a/c XX2244 on 01-09-26 from a/c XX8890 (UPI Ref no 733411225566)",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(TransactionType.CREDIT)
        // The sender's tail - NOT 2244, the user's own receiving account.
        assertThat(result.merchantName).isEqualTo("A/c XX8890")
        assertThat(result.accountLast4).isEqualTo("2244")
    }

    @Test
    fun `neither purpose nor counterparty leaves the title null - no placeholder`() {
        val result =
            parser.parse(
                "VM-HDFCBK-S",
                "Rs.500.00 debited from your HDFC Bank A/c XX1177 on 01-01-26. Avl bal Rs 9,100.00",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.merchantName).isNull()
    }

    @Test
    fun `merchant-bearing message keeps its merchant - fallbacks never override`() {
        val result =
            parser.parse(
                "VM-HDFCBK-S",
                "Sent Rs.500.00 From HDFC Bank A/C x1234 To SWIGGY On 12/07/26 Ref 519912345678 Not You? Call 18002586161",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.merchantName).isEqualTo("SWIGGY")
    }

    @Test
    fun `a debit's own from-account never becomes the title when no receiver is named`() {
        val result =
            parser.parse(
                "VM-HDFCBK-S",
                "Rs.2,000.00 withdrawn from a/c XX6633 on 02-09-26 at ATM. Avl bal Rs 4,000.00",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(TransactionType.DEBIT)
        assertThat(result.merchantName).isNotEqualTo("A/c XX6633")
    }
}
