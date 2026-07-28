package app.clearsms.domain.parser

import app.clearsms.domain.model.ReminderType
import app.clearsms.domain.model.TransactionType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

/**
 * A credit-card bill "due" notice ("Payment of INR X for <Bank> Credit Card
 * no. XX#### is due on <date> ... Ignore if paid") announces a FUTURE
 * obligation. Its trailing "Ignore if paid" advisory satisfies the debit
 * keyword heuristics, so before the guard it surfaced as a bogus DEBIT
 * transaction (user-reported, screenshot-confirmed). It must instead yield a
 * CREDIT_CARD reminder carrying the total due, minimum due and due date —
 * and zero transaction rows.
 */
class BillDueNoticeTest {
    private val transactionParser = TransactionParser()
    private val reminderParser = ReminderParser()

    private val axisBill1 =
        "Payment of INR 532.62 for Axis Bank Credit Card no. XX5106 is due on " +
            "04-04-26 with minimum amount due of INR 100. Ignore if paid."
    private val axisBill2 =
        "Payment of INR 12374.57 for Axis Bank Credit Card no. XX0266 is due on " +
            "04-04-26 with minimum amount due of INR 248. Ignore if paid."

    @Test
    fun `axis card bill-due notice is never a transaction`() {
        assertThat(transactionParser.parse("AX-AXISBK-S", axisBill1)).isNull()
        assertThat(transactionParser.parse("AX-AXISBK-S", axisBill2)).isNull()
    }

    @Test
    fun `axis card bill-due notice is flagged as a statement notice for the rule-extract path`() {
        // The repository nulls rule-derived transactions on this flag too, so
        // a transaction-extracting rule can never resurrect the bogus debit.
        assertThat(transactionParser.isStatementNotice(axisBill1)).isTrue()
        assertThat(transactionParser.isStatementNotice(axisBill2)).isTrue()
    }

    @Test
    fun `axis card bill-due notice yields a credit-card reminder with total min and due date`() {
        val reminder = reminderParser.parse("AX-AXISBK-S", axisBill1)
        assertThat(reminder).isNotNull()
        assertThat(reminder!!.type).isEqualTo(ReminderType.CREDIT_CARD)
        assertThat(reminder.totalDue).isEqualTo(532.62)
        assertThat(reminder.minDue).isEqualTo(100.0)
        assertThat(reminder.dueDate).isEqualTo(LocalDate.of(2026, 4, 4))
        assertThat(reminder.accountLast4).isEqualTo("5106")
        assertThat(reminder.bankName).isEqualTo("Axis Bank")
    }

    @Test
    fun `second axis fixture yields its own totals`() {
        val reminder = reminderParser.parse("AX-AXISBK-S", axisBill2)
        assertThat(reminder).isNotNull()
        assertThat(reminder!!.totalDue).isEqualTo(12374.57)
        assertThat(reminder.minDue).isEqualTo(248.0)
        assertThat(reminder.dueDate).isEqualTo(LocalDate.of(2026, 4, 4))
        assertThat(reminder.accountLast4).isEqualTo("0266")
    }

    @Test
    fun `biller bill-due notice is not a transaction either`() {
        // Same class from a telecom biller — "please pay before the due date"
        // must not move money.
        val body =
            "Hi, a payment of Rs. 599 is due on 15-09-26 for your Airtel Mobile " +
                "9812345670 , please pay before the due date to enjoy services."
        assertThat(transactionParser.parse("AD-AIRTEL-S", body)).isNull()
    }

    @Test
    fun `near miss - payment received on the card is still a credit transaction`() {
        // Completed movement, no "is due": the guard must not over-suppress.
        val body = "Payment of INR 5000.00 received towards your Axis Bank Credit Card no. XX5106. Thank you."
        val tx = transactionParser.parse("AX-AXISBK-S", body)
        assertThat(tx).isNotNull()
        assertThat(tx!!.type).isEqualTo(TransactionType.CREDIT)
        assertThat(tx.amount).isEqualTo(5000.0)
    }

    @Test
    fun `near miss - a completed card debit still parses as a transaction`() {
        val body =
            "Spent Rs. 1299.00 on HDFC Bank Card XX5106 at AMAZON on 20-07-26. " +
                "Avl Limit INR 50,000.00. Not you? Call 18002586161."
        val tx = transactionParser.parse("VM-HDFCBK", body)
        assertThat(tx).isNotNull()
        assertThat(tx!!.type).isEqualTo(TransactionType.DEBIT)
        assertThat(tx.amount).isEqualTo(1299.0)
    }
}
