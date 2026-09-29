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
 * the candidate. (2) The transfer-rail code hyphenated directly onto the
 * descriptor ("TPT-<label>-<name>") is DROPPED along with the leading masked
 * reference: the rail says how the money moved, the label says why, and a
 * rule should match the label without anyone typing "TPT-". Trimming only
 * applies when a descriptor follows the rail's own hyphen - a bare "NEFT
 * transaction" purpose and "ACH C- SAL-<employer>" (rail, "C", hyphen: not
 * a rail prefix) both stay whole.
 *
 * All tails, amounts and names are synthetic.
 */
class DepositDescriptorTitleTest {
    private val parser = TransactionParser()

    private val boilerplate = " Cheque deposits in A/C are subject to clearing"

    @Test
    fun `ach salary deposit stays whole - ACH C- is not a rail prefix - and never titles as clearing`() {
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
    fun `tpt deposit drops the masked reference and the rail code, ignores the boilerplate`() {
        val result =
            parser.parse(
                "VM-HDFCBK-S",
                "Update! INR 18,250.00 deposited in HDFC Bank A/c XX3382 on 20-SEP-26 " +
                    "for XXXXXXXXXX5610-TPT-FlatShareRefund-MEERA NAIR.Avl bal INR 64,310.20.$boilerplate",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(TransactionType.CREDIT)
        assertThat(result.merchantName).isEqualTo("FlatShareRefund-MEERA NAIR")
        assertThat(result.accountLast4).isEqualTo("3382")
        assertThat(result.balance).isEqualTo(64310.20)
    }

    @Test
    fun `the operator's tpt shape - reference and TPT- gone, label and payer name kept`() {
        val result =
            parser.parse(
                "VM-HDFCBK-S",
                "Update! INR 2,500.00 deposited in HDFC Bank A/c XX4471 on 28-SEP-26 " +
                    "for XXXXXXXXXX8823-TPT-GiftForBirthday-ROHAN VERMA.Avl bal INR 31,905.10.$boilerplate",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.merchantName).isEqualTo("GiftForBirthday-ROHAN VERMA")
    }

    @Test
    fun `a bare rail purpose survives whole - trimming needs a descriptor after the rail`() {
        val result =
            parser.parse(
                "VM-HDFCBK-S",
                "Amt Deducted! Rs.41,000.00 from your HDFC Bank A/c XX4522 for NEFT transaction via HDFC Bank Online Banking",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.merchantName).isEqualTo("NEFT transaction")
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
