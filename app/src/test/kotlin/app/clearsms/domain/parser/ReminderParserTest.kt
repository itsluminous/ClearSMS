package app.clearsms.domain.parser

import app.clearsms.domain.model.ReminderType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class ReminderParserTest {
    private val parser = ReminderParser()

    // region acceptance

    @Test
    fun `credit card bill with total and min due`() {
        val result =
            parser.parse(
                "HDFCBK",
                "Your HDFC Bank Credit Card XX4400 statement: Total due Rs.15,240.00, Min due Rs.762.00. Pay by 05-08-26 to avoid charges.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.CREDIT_CARD)
        assertThat(result.totalDue).isEqualTo(15240.0)
        assertThat(result.minDue).isEqualTo(762.0)
        assertThat(result.dueDate).isEqualTo(LocalDate.of(2026, 8, 5))
        assertThat(result.accountLast4).isEqualTo("4400")
        assertThat(result.bankName).isEqualTo("HDFC Bank")
    }

    @Test
    fun `emi reminder with four digit year`() {
        val result =
            parser.parse(
                "AXISBK",
                "EMI of Rs.12,500 for your loan account is due on 10/08/2026. Please maintain sufficient balance.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.EMI)
        assertThat(result.dueDate).isEqualTo(LocalDate.of(2026, 8, 10))
    }

    @Test
    fun `insurance premium with month name date`() {
        val result =
            parser.parse(
                "LICIND",
                "Your policy premium of Rs.24,000 is due on 15-Aug-26. Renew before the due date to keep your cover active.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.INSURANCE)
        assertThat(result.dueDate).isEqualTo(LocalDate.of(2026, 8, 15))
    }

    @Test
    fun `subscription renewal with expiry date`() {
        val result =
            parser.parse(
                "NETFLX",
                "Your annual plan expires on 01-09-2026. Renew now to continue enjoying benefits.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.SUBSCRIPTION)
        assertThat(result.dueDate).isEqualTo(LocalDate.of(2026, 9, 1))
    }

    @Test
    fun `electricity bill with due date is an OTHER reminder`() {
        val result =
            parser.parse(
                "BSESDL",
                "Your electricity bill of Rs.2,340 for Jul is generated. Pay by 12-08-2026 to avoid late fee.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.OTHER)
        assertThat(result.dueDate).isEqualTo(LocalDate.of(2026, 8, 12))
    }

    // endregion

    // region rejection: no due date means no reminder

    @Test
    fun `credit card bill without a due date is rejected even with amounts`() {
        assertThat(
            parser.parse(
                "ICICIB",
                "Credit card bill generated. Total due Rs.5,000.00 and Min due Rs.250.00. Please pay at the earliest.",
            ),
        ).isNull()
    }

    @Test
    fun `reimbursement claim with an amount but no due date is rejected`() {
        assertThat(
            parser.parse(
                "MEDIBD",
                "Dear member, your claim of Rs.4,500 towards hospitalisation has been approved. Payment will follow.",
            ),
        ).isNull()
    }

    @Test
    fun `generic bill mention with no date is rejected`() {
        assertThat(parser.parse("SOMESVC", "Your bill is ready. Payment reminder: kindly clear your dues.")).isNull()
    }

    // endregion

    // region rejection: settled or completed events

    @Test
    fun `payment received confirmation is rejected`() {
        assertThat(
            parser.parse(
                "HDFCBK",
                "Payment received: Rs.15,240 towards your HDFC Credit Card XX4400. Next statement due on 05-09-26.",
            ),
        ).isNull()
    }

    @Test
    fun `refund confirmation is rejected`() {
        assertThat(
            parser.parse(
                "AMAZIN",
                "Your refund of Rs.1,299 for order 403-1234567 has been processed and is due by 05-08-26.",
            ),
        ).isNull()
    }

    @Test
    fun `thank you for payment is rejected`() {
        assertThat(
            parser.parse("VODAID", "Thank you for your payment of Rs.599. Your postpaid plan is active till 05-08-26."),
        ).isNull()
    }

    @Test
    fun `debit transaction confirmation is rejected`() {
        assertThat(
            parser.parse("HDFCBK", "Rs.500 debited from A/c XX1234 on 12-07-26. Avl Bal Rs.1000.00"),
        ).isNull()
    }

    // endregion

    // region rejection: unanchored due context

    @Test
    fun `bare due keyword far from any date is rejected`() {
        assertThat(
            parser.parse(
                "SOMESVC",
                "Your report is due for review. It was submitted on 12-07-26 and the fee was Rs.100.",
            ),
        ).isNull()
    }

    @Test
    fun `plain message is not a reminder`() {
        assertThat(parser.parse("FRIEND", "Are we meeting tomorrow at 6?")).isNull()
    }

    // endregion

    @Test
    fun `parseDate handles all supported formats`() {
        assertThat(parser.parseDate("31-12-26")).isEqualTo(LocalDate.of(2026, 12, 31))
        assertThat(parser.parseDate("5 Aug 2026")).isEqualTo(LocalDate.of(2026, 8, 5))
        assertThat(parser.parseDate("2026-08-05")).isEqualTo(LocalDate.of(2026, 8, 5))
        assertThat(parser.parseDate("no date here")).isNull()
        assertThat(parser.parseDate("99-99-99")).isNull()
    }
}
