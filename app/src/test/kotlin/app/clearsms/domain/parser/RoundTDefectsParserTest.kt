package app.clearsms.domain.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

/**
 * Regression fixtures for the round-T reported defects (structure preserved
 * from real user messages; digits altered).
 *
 * Decisions encoded here:
 * - A card-bill notice carries BOTH a total ("Payment of INR 14683.41 ... is
 *   due") and a minimum ("minimum amount due of INR 881"): the TOTAL is the
 *   reminder's headline amount, the minimum stays secondary, and the
 *   invariant totalDue >= minDue holds.
 * - A FUTURE-tense debit ("will be debited for Rs 59.00 on 03-Jul-26") is an
 *   upcoming obligation: a REMINDER carrying the amount, the date and the
 *   payee — never a completed transaction. The settled-payment guard's bare
 *   "debited" must not veto it.
 * - An insurance premium whose amount follows the policy number ("policy no.
 *   H4847657 of Rs. 1250") still yields the amount, the plan label and the
 *   due date. Its "Kindly ignore if paid" advisory must not fake a debit.
 * - Mandate created/cancelled notices stay as they were: no reminder (no due
 *   obligation), no transaction.
 */
class RoundTDefectsParserTest {
    private val reminderParser = ReminderParser()
    private val transactionParser = TransactionParser()
    private val otpParser = OtpParser()

    // region defect 1+2 — card bill: total headline, minimum secondary

    private val axisCardBill =
        "Payment of INR 14683.41 for Axis Bank Credit Card no. XX5106 is due on 04-08-26 " +
            "with minimum amount due of INR 881. Ignore if paid."

    @Test
    fun `card bill reminder carries the TOTAL as its amount`() {
        val reminder = reminderParser.parse("AX-AXISBK", axisCardBill)
        assertThat(reminder).isNotNull()
        assertThat(reminder!!.totalDue).isEqualTo(14683.41)
        assertThat(reminder.dueDate).isEqualTo(LocalDate.of(2026, 8, 4))
    }

    @Test
    fun `card bill reminder keeps the minimum secondary and the invariant holds`() {
        val reminder = reminderParser.parse("AX-AXISBK", axisCardBill)!!
        assertThat(reminder.minDue).isEqualTo(881.0)
        assertThat(reminder.totalDue!!).isAtLeast(reminder.minDue!!)
        assertThat(reminder.accountLast4).isEqualTo("5106")
    }

    @Test
    fun `card bill notice never becomes a transaction`() {
        assertThat(transactionParser.parse("AX-AXISBK", axisCardBill)).isNull()
        assertThat(transactionParser.isStatementNotice(axisCardBill)).isTrue()
    }

    // endregion

    // region defect 3 — upcoming UPI autopay mandate debit

    private val iciciAutopay =
        "ICICI Bank SAVINGS Account XX222 will be debited for Rs 59.00 on 03-Jul-26 towards " +
            "Autopay for YouTube, UPI Mandate, Unique Mandate Number 4e6c1da5c2cbf333e063b22fb00aef94@oksbi"

    @Test
    fun `upcoming autopay debit is a reminder with amount date and account`() {
        val reminder = reminderParser.parse("VM-ICICIB", iciciAutopay)
        assertThat(reminder).isNotNull()
        assertThat(reminder!!.totalDue).isEqualTo(59.0)
        assertThat(reminder.dueDate).isEqualTo(LocalDate.of(2026, 7, 3))
        assertThat(reminder.accountLast4).isEqualTo("222")
    }

    @Test
    fun `upcoming autopay debit is labelled with the payee`() {
        assertThat(reminderParser.parse("VM-ICICIB", iciciAutopay)!!.label).isEqualTo("YouTube autopay")
    }

    @Test
    fun `upcoming autopay debit never becomes a transaction`() {
        // "will be debited" is future tense — nothing has moved.
        assertThat(transactionParser.parse("VM-ICICIB", iciciAutopay)).isNull()
    }

    @Test
    fun `hdfc card autopay alert also yields a dated reminder with its amount`() {
        val body =
            "Alert:\nINR.649.00 will be debited on 12/08/2026 from HDFC Bank Card 4321 for Amazon Seller Servic..\n" +
                "ID:YJUqRxTlbN\nAct:https://hdfcbk.io/HDFCBK/a/1234ENb"
        val reminder = reminderParser.parse("AD-HDFCBK", body)
        assertThat(reminder).isNotNull()
        assertThat(reminder!!.totalDue).isEqualTo(649.0)
        assertThat(reminder.dueDate).isEqualTo(LocalDate.of(2026, 8, 12))
        assertThat(transactionParser.parse("AD-HDFCBK", body)).isNull()
    }

    @Test
    fun `a collect request with no date never becomes a reminder or a transaction`() {
        val body =
            "Hi, MEESHO TECHNOLOGIES PRIVATE LIMITED has requested money from you on Tata Neu. " +
                "On approving the request, Rs.331.00 will be debited from your account. To authorise, " +
                "click on https://m.tneu.in/MYTNEU/LJqzNy1 or go to the Tata Pay UPI 'Approve Requests' section."
        assertThat(reminderParser.parse("VM-MYTNEU", body)).isNull()
        assertThat(transactionParser.parse("VM-MYTNEU", body)).isNull()
    }

    @Test
    fun `mandate created and cancelled notices stay reminder-free and transaction-free`() {
        val created =
            "Mandate towards Netflix has been successfully created for Rs 649.00 via UPI on your ICICI Bank account."
        val cancelled =
            "You have successfully cancelled the scheduled monthly payment of Rs 649.00 towards Netflix. Mandate was cancelled."
        for (body in listOf(created, cancelled)) {
            assertThat(reminderParser.parse("VM-ICICIB", body)).isNull()
            assertThat(transactionParser.parse("VM-ICICIB", body)).isNull()
        }
    }

    @Test
    fun `a genuine completed debit still parses as a transaction`() {
        val result =
            transactionParser.parse(
                "VM-ICICIB",
                "INR 2,499.00 debited from a/c XX222 on 01-08-26 towards Amazon. Avl Bal INR 40,194.56.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.amount).isEqualTo(2499.0)
        // ...and a completed debit is never an upcoming-debit reminder.
        assertThat(
            reminderParser.parse(
                "VM-ICICIB",
                "INR 2,499.00 debited from a/c XX222 on 01-08-26 towards Amazon. Avl Bal INR 40,194.56.",
            ),
        ).isNull()
    }

    // endregion

    // region defect 4 — premium amount after the policy number

    private val iciciPruPremium =
        "Dear valued customer, premium due on 15-Jul-26 for your ICICIPru policy ICICI Pru iProtect Smart " +
            "policy no. H4847657 of Rs. 1250 will be deducted as per standing instructions. " +
            "Kindly ignore if paid. T&C apply."

    @Test
    fun `premium reminder extracts the amount that follows the policy number`() {
        val reminder = reminderParser.parse("JD-ICICIP", iciciPruPremium)
        assertThat(reminder).isNotNull()
        assertThat(reminder!!.totalDue).isEqualTo(1250.0)
        assertThat(reminder.dueDate).isEqualTo(LocalDate.of(2026, 7, 15))
    }

    @Test
    fun `premium reminder is typed insurance and labelled with the plan`() {
        val reminder = reminderParser.parse("JD-ICICIP", iciciPruPremium)!!
        assertThat(reminder.type.name).isEqualTo("INSURANCE")
        assertThat(reminder.label).isEqualTo("ICICI Pru iProtect Smart")
    }

    @Test
    fun `premium notice with ignore-if-paid advisory never becomes a transaction`() {
        // "Kindly ignore if paid" carries a completed-tense "paid" that used
        // to satisfy the debit heuristics and fake a Rs 1250 debit.
        assertThat(transactionParser.parse("JD-ICICIP", iciciPruPremium)).isNull()
        assertThat(transactionParser.isStatementNotice(iciciPruPremium)).isTrue()
    }

    @Test
    fun `plan-less premium variant also extracts its amount`() {
        val body =
            "Dear valued customer, premium due on 15-Jun-26 for your ICICIPru policy no. H1234567 of Rs.1250 " +
                "will be deducted as per standing instructions. Kindly ignore if paid. T&C apply."
        val reminder = reminderParser.parse("JX-ICICIP", body)
        assertThat(reminder).isNotNull()
        assertThat(reminder!!.totalDue).isEqualTo(1250.0)
    }

    // endregion

    // region defect 5 — transaction OTPs

    private val axisTxnOtp =
        "413423 is SECRET OTP for txn of INR 1205.23 on Axis Bank card XX0266 at AIRTEL PAY on " +
            "01-08-26 18:57:01. OTP valid for 5 mins. Please do not share this OTP."

    @Test
    fun `secret otp phrasing is keyword-anchored`() {
        assertThat(otpParser.parseAnchored(axisTxnOtp)?.code).isEqualTo("413423")
    }

    @Test
    fun `transaction otp never becomes a transaction`() {
        assertThat(transactionParser.parse("AD-AXISBK", axisTxnOtp)).isNull()
    }

    @Test
    fun `a spend alert that merely mentions otp in an advisory is not an anchored otp`() {
        val body =
            "ALERT:Rs.740 spent via CREDIT Card xx0266 at AMAZON on 2026-08-01:18:57:01 without " +
                "PIN/OTP.Not you?Call 08061914588."
        assertThat(otpParser.parseAnchored(body)).isNull()
        // ...and it still parses as a transaction.
        assertThat(transactionParser.parse("VM-HDFCBK", body)).isNotNull()
    }

    // endregion
}
