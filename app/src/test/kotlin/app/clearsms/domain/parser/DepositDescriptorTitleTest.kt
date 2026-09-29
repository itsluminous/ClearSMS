package app.clearsms.domain.parser

import app.clearsms.domain.model.TransactionType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * HDFC "deposited in ... for <descriptor>" credits that end with the cheque
 * boilerplate "Cheque deposits in A/C are subject to clearing".
 *
 * Two defects met here. (1) The merchant regex ("to|at|towards <name>")
 * captured "clearing" out of "subject to clearing", and a merchant beats
 * the purpose clause, so every such row was titled "clearing". A "to" led
 * by a CONDITION word ("subject to", "due to", "refer to") introduces a
 * condition, never a payee - the [GuardId.CONDITIONAL_LEAD] guard rejects
 * the candidate. (2) The transfer-rail code (TPT/ACH/NEFT...) hyphenated
 * onto the descriptor is now KEPT: the operator wants the descriptor as
 * the bank wrote it, minus only the leading masked reference.
 *
 * All tails, amounts and names are synthetic.
 */
class DepositDescriptorTitleTest {
    private val parser = TransactionParser()

    private val boilerplate = " Cheque deposits in A/C are subject to clearing"

    @Test
    fun `ach salary deposit is titled by its whole descriptor, not by the clearing boilerplate`() {
        val result =
            parser.parse(
                "VM-HDFCBK-S",
                "Update! INR 1,23,450.00 deposited in HDFC Bank A/c XX7719 on 29-SEP-26 " +
                    "for ACH C- SAL-ACMECORP-PAYROLLHUB.Avl bal INR 2,10,880.55.$boilerplate",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(TransactionType.CREDIT)
        assertThat(result.merchantName).isEqualTo("ACH C- SAL-ACMECORP-PAYROLLHUB")
        assertThat(result.accountLast4).isEqualTo("7719")
        assertThat(result.balance).isEqualTo(210880.55)
    }

    @Test
    fun `tpt deposit keeps the rail code, drops only the masked reference, ignores the boilerplate`() {
        val result =
            parser.parse(
                "VM-HDFCBK-S",
                "Update! INR 18,250.00 deposited in HDFC Bank A/c XX3382 on 20-SEP-26 " +
                    "for XXXXXXXXXX5610-TPT-FlatShareRefund-MEERA NAIR.Avl bal INR 64,310.20.$boilerplate",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(TransactionType.CREDIT)
        assertThat(result.merchantName).isEqualTo("TPT-FlatShareRefund-MEERA NAIR")
        assertThat(result.accountLast4).isEqualTo("3382")
        assertThat(result.balance).isEqualTo(64310.20)
    }

    @Test
    fun `the boilerplate alone never yields a merchant - the title stays null`() {
        val result =
            parser.parse(
                "VM-HDFCBK-S",
                "Rs.500.00 debited from your HDFC Bank A/c XX1177 on 01-01-26. Avl bal Rs 9,100.00.$boilerplate",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.merchantName).isNull()
    }

    @Test
    fun `a genuine to-merchant capture still wins when the boilerplate follows it`() {
        val result =
            parser.parse(
                "VK-ICICIT",
                "Rs.250.00 debited from A/c XX9805 to VPA shopkeeper@okicici on 20-07-26. " +
                    "Avl Bal Rs.5,000.25.$boilerplate",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.merchantName).isEqualTo("shopkeeper@okicici")
    }

    @Test
    fun `a genuine to-merchant capture is unaffected elsewhere`() {
        val result =
            parser.parse(
                "VM-HDFCBK-S",
                "Sent Rs.500.00 From HDFC Bank A/C x1234 To SWIGGY On 12/07/26 Ref 519912345678 Not You? Call 18002586161",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.merchantName).isEqualTo("SWIGGY")
    }

    @Test
    fun `a condition word before to blocks other boilerplate shapes too`() {
        // "due to" - a reversal reason, not a payee.
        val result =
            parser.parse(
                "VM-HDFCBK-S",
                "Rs.1,000.00 credited to your HDFC Bank A/c XX1177 on 02-01-26 due to reversal. Avl bal Rs 10,100.00",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.merchantName).isNotEqualTo("reversal")
    }
}
