package app.clearsms.notification

import app.clearsms.notification.TransactionNotifier.Content
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Parsed notifications must render every figure in the MESSAGE's own
 * currency, not a fixed one.
 *
 * Debit and credit headlines already did. Four other figures - a bill
 * total, a plain balance, a payment request and the secondary minimum -
 * were formatted with a hardcoded INR code, so a user in Pakistan, Vietnam
 * or Germany saw a rupee symbol on their own bank's balance. The currency
 * was already resolved on the way in; these sites simply bypassed it.
 *
 * The fallback for a record with no currency at all stays INR, because
 * that is the encoding every pre-v23 row uses - so nothing changes for
 * existing Indian users, which the last test here pins.
 */
class NotificationCurrencyTest {
    private fun build(details: Map<String, String>) =
        TransactionNotifier.buildContent(
            details = details,
            balanceUpdateLabel = "Balance update",
            accountFormat = "A/c %1\$s",
            dueDateFormat = "Due %1\$s",
            minDueFormat = "Min due %1\$s",
        )

    @Test
    fun `a bill total renders in the message's own currency`() {
        val content =
            build(
                mapOf(
                    "total_due" to "7777.77",
                    "due_date" to "2026-11-15",
                    "bank" to "Meezan Bank",
                    "currency" to "PKR",
                ),
            )!!
        assertThat(content.kind).isEqualTo(Content.Kind.BALANCE)
        assertThat(content.title).doesNotContain("₹")
        assertThat(content.title).contains("7,777.77")
    }

    @Test
    fun `a bill minimum renders in the message's own currency`() {
        val content =
            build(
                mapOf(
                    "total_due" to "7777.77",
                    "min_due" to "500.0",
                    "due_date" to "2026-11-15",
                    "currency" to "PKR",
                ),
            )!!
        // The secondary figure sits on the detail line next to the headline;
        // the two must not disagree about the currency.
        assertThat(content.text).doesNotContain("₹")
    }

    @Test
    fun `a plain balance renders in the message's own currency`() {
        val content = build(mapOf("balance" to "1234.56", "bank" to "HBL", "currency" to "PKR"))!!
        assertThat(content.kind).isEqualTo(Content.Kind.BALANCE)
        assertThat(content.title).doesNotContain("₹")
        assertThat(content.title).contains("1,234.56")
    }

    @Test
    fun `a payment request renders in the message's own currency`() {
        val content =
            build(
                mapOf(
                    "requested_amount" to "250.0",
                    "merchant" to "Sample Store",
                    "currency" to "VND",
                ),
            )!!
        assertThat(content.kind).isEqualTo(Content.Kind.BALANCE)
        assertThat(content.title).doesNotContain("₹")
    }

    @Test
    fun `a euro balance renders in euros`() {
        val content = build(mapOf("balance" to "99.5", "currency" to "EUR"))!!
        assertThat(content.title).doesNotContain("₹")
        // Euro formatting uses a comma decimal separator ("€99,50"), so
        // assert the symbol rather than a digit string.
        assertThat(content.title).contains("€")
    }

    @Test
    fun `debit and credit headlines still carry their own currency`() {
        val debit = build(mapOf("amount" to "100.0", "type" to "debit", "currency" to "PKR"))!!
        val credit = build(mapOf("amount" to "100.0", "type" to "credit", "currency" to "PKR"))!!
        assertThat(debit.title).startsWith("\u2212 ")
        assertThat(credit.title).startsWith("+ ")
        assertThat(debit.title).doesNotContain("₹")
        assertThat(credit.title).doesNotContain("₹")
    }

    @Test
    fun `a record with no currency still renders as rupees`() {
        // Every pre-v23 row encodes rupees by omitting the key. This is the
        // regression guard for existing Indian users: the fix must not have
        // moved them off the rupee symbol.
        val bill = build(mapOf("total_due" to "14683.41", "due_date" to "2026-08-04"))!!
        val balance = build(mapOf("balance" to "1234.56"))!!
        val request = build(mapOf("requested_amount" to "250.0", "merchant" to "Sample Store"))!!
        assertThat(bill.title).isEqualTo("₹14,683.41")
        assertThat(balance.title).isEqualTo("₹1,234.56")
        assertThat(request.title).isEqualTo("₹250")
    }

    @Test
    fun `an indian bill with an explicit INR currency is unchanged`() {
        val content =
            build(
                mapOf(
                    "total_due" to "14683.41",
                    "min_due" to "881.0",
                    "due_date" to "2026-08-04",
                    "currency" to "INR",
                ),
            )!!
        assertThat(content.title).isEqualTo("₹14,683.41")
        assertThat(content.text).contains("Min due ₹881")
    }
}
