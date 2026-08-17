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

    @Test
    fun `broadband bill with amount stated after is extracts total and account tail`() {
        // Defect P1a: "your bill for <MON-YY> on A/C <long number> is INR <amt>"
        // used to classify as a bill but drop the amount and the account.
        val result =
            parser.parse(
                "QP-ACTCRP",
                "Patron, Your bill for JUL-26 on A/C 102017641550 is INR 1178.82. Due date:15-JUL-26. " +
                    "Pay @https://tiny.example.in/ACTGRP/kdkgN?accountNo=102017641550. Team ACT",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.OTHER)
        assertThat(result.totalDue).isEqualTo(1178.82)
        assertThat(result.dueDate).isEqualTo(LocalDate.of(2026, 7, 15))
        // The long account number yields only its masked TAIL, like other billers.
        assertThat(result.accountLast4).isEqualTo("1550")
    }

    @Test
    fun `bill-is pattern does not bind an unrelated amount later in the body`() {
        // Near-miss for P1a: "is" not followed directly by a currency amount
        // must not let the pattern jump to a different amount.
        val result =
            parser.parse(
                "QP-ACTCRP",
                "Patron, Your bill for JUL-26 is ready. Recharge offer of INR 239.00 available. Due date:15-JUL-26. Team ACT",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.totalDue).isNull()
    }

    @Test
    fun `card statement with Total amt and Min amt due maps each to its own field`() {
        // Defect P1b: "Total amt:" (no "due") failed the total pattern while
        // "Min amt due:" matched - the total was lost and only the min stored.
        val result =
            parser.parse(
                "AD-AXISBK",
                "Your statement for Axis Bank Credit Card no. XX0266 is generated.\n" +
                    "Due on: 04-08-26\n" +
                    "Total amt: INR  Dr. 12374.57\n" +
                    "Min amt due: INR  Dr. 248.00\n" +
                    "Pay at axis.bank.in/ccpaynow",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.CREDIT_CARD)
        assertThat(result.totalDue).isEqualTo(12374.57)
        assertThat(result.minDue).isEqualTo(248.0)
        assertThat(result.totalDue!!).isAtLeast(result.minDue!!)
        assertThat(result.dueDate).isEqualTo(LocalDate.of(2026, 8, 4))
        assertThat(result.accountLast4).isEqualTo("0266")
        assertThat(result.bankName).isEqualTo("Axis Bank")
    }

    @Test
    fun `total smaller than min is a mis-parse and is re-resolved to a valid total`() {
        // The loose "total of" phrase grabs 100, which contradicts min=250 -
        // the parser must re-resolve to the statement amount instead.
        val result =
            parser.parse(
                "ICICIB",
                "Card update: total of Rs 100 reward points earned. Statement of INR 4,000.00 generated, " +
                    "minimum of Rs 250.00 is due by 05-08-26 on your credit card.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.minDue).isEqualTo(250.0)
        assertThat(result.totalDue).isEqualTo(4000.0)
        assertThat(result.totalDue!!).isAtLeast(result.minDue!!)
    }

    @Test
    fun `total that contradicts min with no valid alternative is dropped not stored`() {
        val result =
            parser.parse(
                "HDFCBK",
                "Payment of INR 100.00 is due on 05-08-26 for your credit card. Min amt due: INR 500.00.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.minDue).isEqualTo(500.0)
        // Storing 100 as the total would violate totalDue >= minDue.
        assertThat(result.totalDue).isNull()
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

    @Test
    fun `yearless day-month resolves to the year on or closest after the anchor`() {
        val anchor = LocalDate.of(2026, 8, 16)
        // Future within the same year stays in that year.
        assertThat(parser.parseDate("20 Aug", anchor)).isEqualTo(LocalDate.of(2026, 8, 20))
        // Same-day is valid (a boarding-day itinerary refers to TODAY's flight).
        assertThat(parser.parseDate("16Aug", anchor)).isEqualTo(LocalDate.of(2026, 8, 16))
        // A day already past relative to the anchor rolls FORWARD - a notice
        // refers to the near future relative to when it was sent, never back.
        assertThat(parser.parseDate("12Aug", anchor)).isEqualTo(LocalDate.of(2027, 8, 12))
        // Near end of year, an early month resolves to the NEXT year.
        assertThat(parser.parseDate("3Jan", LocalDate.of(2026, 12, 20))).isEqualTo(LocalDate.of(2027, 1, 3))
    }

    @Test
    fun `yearless date anchors on the MESSAGE date - a 2024 itinerary never becomes a 2026 alert`() {
        // Real defect: an IndiGo SMS RECEIVED 11-Dec-2024 saying "11Dec,
        // PAT-BLR" produced a travel card dated 11-Dec-2026 because the
        // inference anchored on the clock at re-sort time. Anchored on the
        // message date it is 2024 - already expired, so it lands in Older.
        val messageDate = LocalDate.of(2024, 12, 11)
        assertThat(parser.parseDate("11Dec", messageDate)).isEqualTo(LocalDate.of(2024, 12, 11))
        // Same-day is valid; the following day would rather roll forward.
        assertThat(parser.parseDate("10Dec", messageDate)).isEqualTo(LocalDate.of(2025, 12, 10))
    }

    @Test
    fun `two-digit-year windowing is fixed to 20xx and never clock-relative`() {
        // Audited alongside the yearless fix: normalizeYear maps yy to
        // 2000+yy unconditionally - explicit-year forms never consult the
        // anchor, so re-sorting old messages cannot move dated bills.
        val farAnchor = LocalDate.of(2099, 1, 1)
        assertThat(parser.parseDate("05-08-26", farAnchor)).isEqualTo(LocalDate.of(2026, 8, 5))
        assertThat(parser.parseDate("05-Aug-26", farAnchor)).isEqualTo(LocalDate.of(2026, 8, 5))
        assertThat(parser.parseDate("Aug 8 26", farAnchor)).isEqualTo(LocalDate.of(2026, 8, 8))
    }

    @Test
    fun `yearless branch never fires when an explicit year is attached`() {
        val anchor = LocalDate.of(2026, 8, 16)
        // Attached year is owned by the dated branches.
        assertThat(parser.parseDate("12Aug26", anchor)).isNull()
        // Apostrophe year ("12Nov'26" in reschedule notices) stays unparsed
        // rather than resolving to an anchor-inferred year.
        assertThat(parser.parseDate("12Nov'26", anchor)).isNull()
        // Not a month.
        assertThat(parser.parseDate("12Xyz", anchor)).isNull()
    }
}
