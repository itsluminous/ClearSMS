package app.clearsms.notification

import app.clearsms.R
import app.clearsms.notification.TransactionNotifier.Content
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Round-T: bill reminders render through the SAME parsed notification as
 * transactions - informational blue treatment, NO sign (a bill is money
 * owed, not money moved), the invariant-checked TOTAL as the headline with
 * the minimum secondary, and the due date visible on the detail line.
 */
class BillNotificationContentTest {
    private fun build(details: Map<String, String>) =
        TransactionNotifier.buildContent(
            details = details,
            balanceUpdateLabel = "Balance update",
            accountFormat = "A/c %1\$s",
            dueDateFormat = "Due %1\$s",
            minDueFormat = "Min due ₹%1\$s",
        )

    private val axisBillDetails =
        mapOf(
            "total_due" to "14683.41",
            "min_due" to "881.0",
            "due_date" to "2026-08-04",
            "account_last4" to "5106",
            "bank" to "Axis Bank",
            "label" to "Axis Bank Credit Card",
        )

    @Test
    fun `bill renders in the informational blue treatment with no sign`() {
        val content = build(axisBillDetails)
        assertThat(content).isNotNull()
        assertThat(content!!.kind).isEqualTo(Content.Kind.BALANCE)
        assertThat(content.title).isEqualTo("₹14,683.41")
        assertThat(content.title).doesNotContain("−")
        assertThat(content.title).doesNotContain("+")
        assertThat(TransactionNotifier.amountColorRes(content.kind)).isEqualTo(R.color.notif_amount_balance)
    }

    @Test
    fun `bill detail line shows due date biller account bank and the minimum`() {
        val content = build(axisBillDetails)!!
        assertThat(content.text).isEqualTo("Due 4 Aug · Axis Bank Credit Card · A/c 5106 · Axis Bank · Min due ₹881")
    }

    @Test
    fun `bill headline is the total even when a generic amount extract equals the minimum`() {
        // A mis-captured rule "amount" (the minimum) must never displace the
        // invariant-checked total as the headline.
        val content = build(axisBillDetails + ("amount" to "881.0"))!!
        assertThat(content.title).isEqualTo("₹14,683.41")
    }

    @Test
    fun `autopay reminder renders amount payee and due date`() {
        val content =
            build(
                mapOf(
                    "total_due" to "59.0",
                    "due_date" to "2026-07-03",
                    "merchant" to "YouTube",
                    "account_last4" to "222",
                    "bank" to "ICICI Bank",
                    "label" to "YouTube autopay",
                ),
            )!!
        assertThat(content.kind).isEqualTo(Content.Kind.BALANCE)
        assertThat(content.title).isEqualTo("₹59")
        assertThat(content.text).isEqualTo("Due 3 Jul · YouTube · A/c 222 · ICICI Bank")
    }

    @Test
    fun `bill with a debit type stamped still renders blue and unsigned`() {
        val content = build(axisBillDetails + ("type" to "debit") + ("amount" to "14683.41"))!!
        assertThat(content.kind).isEqualTo(Content.Kind.BALANCE)
        assertThat(content.title).isEqualTo("₹14,683.41")
    }

    @Test
    fun `bill with a due date but no amounts yields nothing so the plain notification runs`() {
        assertThat(build(mapOf("due_date" to "2026-08-04"))).isNull()
    }

    @Test
    fun `a debit still renders signed and red - bills changed nothing for transactions`() {
        val content = build(mapOf("amount" to "1299.0", "type" to "debit", "merchant" to "Swiggy"))!!
        assertThat(content.kind).isEqualTo(Content.Kind.DEBIT)
        assertThat(content.title).isEqualTo("− ₹1,299")
        assertThat(TransactionNotifier.amountColorRes(content.kind)).isEqualTo(R.color.notif_amount_debit)
    }

    @Test
    fun `non-iso due date never leaks into the detail line`() {
        assertThat(TransactionNotifier.formatDueDate("03-Jul-26")).isNull()
        assertThat(TransactionNotifier.formatDueDate("2026-07-03")).isEqualTo("3 Jul")
    }
}
