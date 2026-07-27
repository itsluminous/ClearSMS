package app.clearsms.notification

import app.clearsms.notification.TransactionNotifier.Content
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TransactionContentTest {
    @Test
    fun `debit gets minus-signed amount and red accent`() {
        val content =
            TransactionNotifier.buildContent(
                details = mapOf("amount" to "1299.0", "type" to "debit", "merchant" to "Swiggy"),
                balanceUpdateLabel = BALANCE_LABEL,
                accountFormat = ACCOUNT_FORMAT,
            )
        assertThat(content).isNotNull()
        assertThat(content!!.kind).isEqualTo(Content.Kind.DEBIT)
        assertThat(content.title).isEqualTo("− ₹1,299")
        assertThat(content.colorArgb).isEqualTo(TransactionNotifier.COLOR_DEBIT)
    }

    @Test
    fun `credit gets plus-signed amount and green accent`() {
        val content =
            TransactionNotifier.buildContent(
                details = mapOf("amount" to "5000.0", "type" to "credit"),
                balanceUpdateLabel = BALANCE_LABEL,
                accountFormat = ACCOUNT_FORMAT,
            )
        assertThat(content!!.kind).isEqualTo(Content.Kind.CREDIT)
        assertThat(content.title).isEqualTo("+ ₹5,000")
        assertThat(content.colorArgb).isEqualTo(TransactionNotifier.COLOR_CREDIT)
    }

    @Test
    fun `balance-only update gets unsigned amount and blue accent`() {
        val content =
            TransactionNotifier.buildContent(
                details = mapOf("balance" to "12430.0", "bank" to "HDFC Bank"),
                balanceUpdateLabel = BALANCE_LABEL,
                accountFormat = ACCOUNT_FORMAT,
            )
        assertThat(content!!.kind).isEqualTo(Content.Kind.BALANCE)
        assertThat(content.title).isEqualTo("₹12,430")
        assertThat(content.colorArgb).isEqualTo(TransactionNotifier.COLOR_BALANCE)
        assertThat(content.text).isEqualTo("Balance update · HDFC Bank")
    }

    @Test
    fun `no amount-type and no balance yields nothing to notify`() {
        val content =
            TransactionNotifier.buildContent(
                details = mapOf("merchant" to "Swiggy"),
                balanceUpdateLabel = BALANCE_LABEL,
                accountFormat = ACCOUNT_FORMAT,
            )
        assertThat(content).isNull()
    }

    @Test
    fun `compact text joins merchant account and bank with separators`() {
        val text =
            TransactionNotifier.compactText(
                merchant = "Swiggy",
                accountLast4 = "2863",
                bank = "HDFC Bank",
                balanceOnly = false,
                balanceUpdateLabel = BALANCE_LABEL,
                accountFormat = ACCOUNT_FORMAT,
            )
        assertThat(text).isEqualTo("Swiggy · A/c 2863 · HDFC Bank")
    }

    @Test
    fun `compact text drops missing fields without dangling separators`() {
        val text =
            TransactionNotifier.compactText(
                merchant = null,
                accountLast4 = "2863",
                bank = null,
                balanceOnly = false,
                balanceUpdateLabel = BALANCE_LABEL,
                accountFormat = ACCOUNT_FORMAT,
            )
        assertThat(text).isEqualTo("A/c 2863")
    }

    @Test
    fun `amounts use indian digit grouping and drop whole-number decimals`() {
        assertThat(TransactionNotifier.grouped(1299.0)).isEqualTo("1,299")
        assertThat(TransactionNotifier.grouped(100000.0)).isEqualTo("1,00,000")
        assertThat(TransactionNotifier.grouped(1234567.89)).isEqualTo("12,34,567.89")
        assertThat(TransactionNotifier.grouped(430.0)).isEqualTo("430")
    }

    private companion object {
        const val BALANCE_LABEL = "Balance update"
        const val ACCOUNT_FORMAT = "A/c %1\$s"
    }
}
