package app.clearsms.domain.parser

import app.clearsms.domain.model.ReminderType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

/**
 * Amount, label and type extraction against the real-world phrasings that
 * used to leave reminder cards showing nothing but a date. All message
 * bodies are synthetic/masked reconstructions of the observed shapes.
 */
class ReminderDetailExtractionTest {
    private val parser = ReminderParser()

    // region amounts

    @Test
    fun `rd installment - Amount INR X Due on`() {
        val result =
            parser.parse(
                "VD-HDFCBK",
                "RD Installment Due! Amount INR 12,345.00 Due on 05-AUG-26 HDFC Bank RD 98765 Check RD statement on the MobileBanking App",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.totalDue).isEqualTo(12345.0)
        assertThat(result.dueDate).isEqualTo(LocalDate.of(2026, 8, 5))
    }

    @Test
    fun `telecom bill - Amount to be paid colon Rs X`() {
        val result =
            parser.parse(
                "AX-AIRBIL",
                "Bill for your Airtel Mobile 9812345678 is ready. Amount to be paid: Rs 649.00 Due Date: 15-06-2026. Pay on the app.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.OTHER)
        assertThat(result.totalDue).isEqualTo(649.0)
    }

    @Test
    fun `insurance premium - policy no for Rs X`() {
        val result =
            parser.parse(
                "VM-IPRUMF",
                "Premium due on 15-May-2026 for your ICICIPru policy ICICI Pru iProtect Smart policy no H1234567 for Rs. 5000",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.INSURANCE)
        assertThat(result.totalDue).isEqualTo(5000.0)
    }

    @Test
    fun `card - Payment of INR X is due with minimum amount due of INR Y`() {
        val result =
            parser.parse(
                "AXISBK",
                "Payment of INR 23456.75 for Axis Bank Credit Card no. XX5678 is due on 12-06-26 with minimum amount due of INR 1173. Ignore if paid.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.CREDIT_CARD)
        assertThat(result.totalDue).isEqualTo(23456.75)
        assertThat(result.minDue).isEqualTo(1173.0)
    }

    @Test
    fun `card - Total of Rs X or minimum of Rs Y is due by`() {
        val result =
            parser.parse(
                "ICICIB",
                "ICICI Bank Credit Card XX4001 Statement is sent to pr******it@example.com. Total of Rs 5,432.10 or minimum of Rs 270.55 is due by 18-JUL-26.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.totalDue).isEqualTo(5432.10)
        assertThat(result.minDue).isEqualTo(270.55)
    }

    @Test
    fun `card e-statement - Total amount due INR Dr X and Minimum amt due INR Dr Y`() {
        val result =
            parser.parse(
                "AXISBK",
                "E-Statement of your Axis Bank Credit Card no. XX5678 has been generated. " +
                    "Total amount due: INR  Dr. 4255.60. Minimum amt due: INR  Dr. 212.75, Due date: 10-06-26. Visit the app.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.totalDue).isEqualTo(4255.60)
        assertThat(result.minDue).isEqualTo(212.75)
    }

    @Test
    fun `card - statement of INR X with due date`() {
        val result =
            parser.parse(
                "AXISBK",
                "Your Axis Bank Credit Card XX5678 statement of INR 15240.50 with due date 20-Dec-26 is generated. Click to view.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.totalDue).isEqualTo(15240.50)
    }

    @Test
    fun `card - kindly pay total due of Rs X or Min Due Rs Y`() {
        val result =
            parser.parse(
                "ICICIB",
                "Kindly pay total due of Rs 9876.54 or Min Due Rs 494.00 by 14-May-26 on ICICI Bank Credit Card XX4315 to avoid reporting to credit bureaus",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.totalDue).isEqualTo(9876.54)
        assertThat(result.minDue).isEqualTo(494.0)
    }

    @Test
    fun `emi of Rs X is extracted`() {
        val result =
            parser.parse(
                "AXISBK",
                "EMI of Rs.12,500 for your loan account is due on 10/08/2026. Please maintain sufficient balance.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.totalDue).isEqualTo(12500.0)
    }

    // endregion

    // region typing: deposits are not loan EMIs

    @Test
    fun `rd installment is a DEPOSIT not an EMI`() {
        val result =
            parser.parse(
                "VM-HDFCBK",
                "RD Installment Due! Amount INR 12,345.00 Due on 05-AUG-26 HDFC Bank RD 98765 Check RD statement on the MobileBanking App",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.DEPOSIT)
    }

    @Test
    fun `recurring deposit phrasing is a DEPOSIT`() {
        val result =
            parser.parse(
                "SBIINB",
                "Your Recurring Deposit installment of Rs 2,000 is due on 03-09-2026. Keep balance in linked account.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.DEPOSIT)
        assertThat(result.totalDue).isEqualTo(2000.0)
    }

    @Test
    fun `loan emi stays an EMI`() {
        val result =
            parser.parse(
                "AXISBK",
                "EMI of Rs.12,500 for your loan account is due on 10/08/2026. Please maintain sufficient balance.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.EMI)
    }

    // endregion

    // region settled-payment guard

    @Test
    fun `thank you for an online payment with txn reference never becomes a reminder`() {
        assertThat(
            parser.parse(
                "JK-TATALI",
                "Thank You for an online payment of Rs.54321 on date 12/05/2026 with Transaction Reference Number 12345678901 " +
                    "towards your Tata AIA Life Insurance Policy. Premium due on 12-05-2027.",
            ),
        ).isNull()
    }

    @Test
    fun `payment of Rs X was received is not a reminder`() {
        assertThat(
            parser.parse(
                "CREDIN",
                "Payment of INR 12,345.00 was received for your Axis Bank credit card 1234-XXXX on 12-Jan-2026. Statement due on 20-01-26.",
            ),
        ).isNull()
    }

    // endregion

    // region labels

    @Test
    fun `deposit label masks the reference down to last four digits`() {
        val result =
            parser.parse(
                "VD-HDFCBK",
                "RD Installment Due! Amount INR 12,345.00 Due on 05-AUG-26 HDFC Bank RD 987654321 Check RD statement",
            )
        assertThat(result!!.label).isEqualTo("RD xx4321")
        assertThat(result.label).doesNotContain("987654321")
    }

    @Test
    fun `telecom bill label names the product`() {
        val result =
            parser.parse(
                "AX-AIRBIL",
                "Bill for your Airtel Mobile 9812345678 is ready. Amount to be paid: Rs 649.00 Due Date: 15-06-2026.",
            )
        assertThat(result!!.label).isEqualTo("Airtel Mobile bill")
    }

    @Test
    fun `insurance label carries the plan name`() {
        val result =
            parser.parse(
                "VM-IPRUMF",
                "Premium due on 15-May-2026 for your ICICIPru policy ICICI Pru iProtect Smart policy no H1234567 for Rs. 5000",
            )
        assertThat(result!!.label).isEqualTo("ICICI Pru iProtect Smart")
    }

    @Test
    fun `card label carries the card product`() {
        val result =
            parser.parse(
                "HDFCBK",
                "Payment of INR 4300 for Tata Neu Infinity HDFC Bank Credit Card is due on 21-06-26 with minimum amount due of INR 215.",
            )
        assertThat(result!!.label).isEqualTo("Tata Neu Infinity HDFC Bank Credit Card")
    }

    @Test
    fun `label falls back to a short digit-masked excerpt`() {
        val result =
            parser.parse(
                "BSESDL",
                "Electricity dues for CA 401234567890 must be paid by 12-08-2026 to avoid disconnection of supply at your premises",
            )
        assertThat(result).isNotNull()
        val label = result!!.label
        assertThat(label).isNotNull()
        assertThat(label!!.length).isAtMost(41)
        assertThat(label).doesNotContain("401234567890")
        assertThat(label).contains("Electricity dues")
    }

    // endregion
}
