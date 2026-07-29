package app.clearsms.domain.parser

import app.clearsms.domain.model.ReminderType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Regression coverage for amount-due phrasings that previously parsed no
 * amount: a label-then-value line, and bank/biller shapes that omit the
 * currency symbol. Fixtures are real user messages (digits altered).
 */
class ReminderAmountDueTest {
    private val parser = ReminderParser()

    @Test
    fun `amount due on the next line with Rs prefix is captured`() {
        val body =
            "Amount Due\nRs.4961 on HDFC Bank Credit Card 2863. Pay instantly by 05/JUL/2026 " +
                "via PayZapp > Bill Pay > Credit Card: https://1.hdfc.bank.in/HDFCBK/s/yjw2Y1g5"
        val r = parser.parse("VM-HDFCBK", body)
        assertThat(r).isNotNull()
        assertThat(r!!.type).isEqualTo(ReminderType.CREDIT_CARD)
        assertThat(r.totalDue).isEqualTo(4961.0)
    }

    @Test
    fun `EMI DUE without a currency symbol is captured`() {
        val body =
            "Generated - E-Statement for HDFC Bank Debit Card EMI Loan 1019060010113406\n" +
                "EMI Due date: 05/NOV/2021\nEMI DUE : 4131\nKindly pay overdue, if any"
        val r = parser.parse("VM-HDFCBK", body)
        assertThat(r).isNotNull()
        assertThat(r!!.type).isEqualTo(ReminderType.EMI)
        assertThat(r.totalDue).isEqualTo(4131.0)
    }

    @Test
    fun `ACT bill Due amount without currency is captured`() {
        val body =
            "Dear Patron,\nYour ACT bill on A/C 102017641550 for month of AUG-2023 " +
                "Due: 1162.3 Due date: 15-AUG-23 Pay @ fb7y.app.link/pay?username=102017641550 ACT"
        val r = parser.parse("VK-ACTCRP", body)
        assertThat(r).isNotNull()
        assertThat(r!!.totalDue).isEqualTo(1162.3)
    }

    @Test
    fun `a due date with no amount does not invent an amount from the day`() {
        // "Due date: 15-AUG-23" must NOT yield totalDue = 15 (the day).
        val body = "Your ACT bill is generated. Due date: 15-AUG-23. Please pay on time. ACT"
        val r = parser.parse("VK-ACTCRP", body)
        // Either not a reminder, or a reminder with no fabricated amount.
        assertThat(r?.totalDue).isNull()
    }

    @Test
    fun `EMI Due date alone is not read as an EMI amount`() {
        val body = "Reminder: EMI Due date: 05/NOV/2021 for your loan. Kindly pay overdue, if any."
        val r = parser.parse("VM-HDFCBK", body)
        assertThat(r?.totalDue).isNull()
    }
}
