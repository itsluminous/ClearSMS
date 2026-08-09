package app.clearsms.domain.parser

import app.clearsms.domain.model.AccountType
import app.clearsms.domain.model.TransactionType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Balance-only statement parsing: state reported, no money moved. The exact
 * user-reported HDFC shape previously matched zero rules AND the parser's
 * balance regex - these tests pin every phrasing the statement parser must
 * recognize, and the near-misses it must leave to the transaction path.
 */
class BalanceStatementParserTest {
    private val parser = TransactionParser()

    private val userFixture =
        "Available Bal in HDFC Bank A/c XX8709 as on yesterday:27-JUL-26 is INR 40,194.56. " +
            "Cheques are subject to clearing.For updated A/C Bal dial 18002703333."

    @Test
    fun `user fixture yields a balance statement and never a transaction`() {
        assertThat(parser.parse("VM-HDFCBK", userFixture)).isNull()
        val statement = parser.parseBalanceStatement("VM-HDFCBK", userFixture)
        assertThat(statement).isNotNull()
        assertThat(statement!!.balance).isEqualTo(40194.56)
        assertThat(statement.accountLast4).isEqualTo("8709")
        assertThat(statement.bankName).isEqualTo("HDFC Bank")
        assertThat(statement.accountType).isEqualTo(AccountType.SAVINGS)
    }

    @Test
    fun `bal on date without is keyword`() {
        val statement =
            parser.parseBalanceStatement(
                "VK-HDFCBK",
                "Available Bal in HDFC Bank A/c XX8709 on 30-MAR-23 INR 2,33,442.76. " +
                    "Cheque Deposits in A/c are subject to clearing.",
            )
        assertThat(statement).isNotNull()
        assertThat(statement!!.balance).isEqualTo(233442.76)
        assertThat(statement.accountLast4).isEqualTo("8709")
    }

    @Test
    fun `avl bal colon shorthand`() {
        val statement = parser.parseBalanceStatement("AX-SBIINB", "Avl Bal: Rs. 5,000.00 in A/c XX4321")
        assertThat(statement).isNotNull()
        assertThat(statement!!.balance).isEqualTo(5000.0)
        assertThat(statement.accountLast4).isEqualTo("4321")
    }

    @Test
    fun `available balance is phrasing`() {
        val statement =
            parser.parseBalanceStatement("VM-ICICIB", "Your Available Balance is Rs.1,234.56 for ICICI Bank Account XX9012")
        assertThat(statement).isNotNull()
        assertThat(statement!!.balance).isEqualTo(1234.56)
        assertThat(statement.accountLast4).isEqualTo("9012")
    }

    @Test
    fun `account bal phrasing`() {
        val statement = parser.parseBalanceStatement("VM-KOTAKB", "A/C Bal is INR 908.10 for Kotak Bank A/c XX7654")
        assertThat(statement).isNotNull()
        assertThat(statement!!.balance).isEqualTo(908.10)
    }

    @Test
    fun `bal as on date phrasing`() {
        val statement = parser.parseBalanceStatement("VM-PNBSMS", "Bal as on 27-JUL-26: Rs 77,000.00 in your PNB A/c XX3210")
        assertThat(statement).isNotNull()
        assertThat(statement!!.balance).isEqualTo(77000.0)
    }

    @Test
    fun `debit quoting avl bal stays a transaction with the balance secondary`() {
        val body = "Rs.1,299.00 debited from HDFC Bank a/c XX2863 to Swiggy. Avl bal Rs.12,430.00"
        val tx = parser.parse("AX-HDFCBK", body)
        assertThat(tx).isNotNull()
        assertThat(tx!!.type).isEqualTo(TransactionType.DEBIT)
        assertThat(tx.amount).isEqualTo(1299.0)
        assertThat(tx.balance).isEqualTo(12430.0)
        // The statement parser must refuse it: the balance already belongs
        // to the transaction, a balance-only update here would double-count.
        assertThat(parser.parseBalanceStatement("AX-HDFCBK", body)).isNull()
    }

    @Test
    fun `implausible issuer is stripped from the statement`() {
        val statement = parser.parseBalanceStatement("VD-FLPKRT", "Avl Bal: Rs. 250.00 for A/c XX1111")
        assertThat(statement).isNotNull()
        assertThat(statement!!.bankName).isNull()
    }

    @Test
    fun `no balance phrase means no statement`() {
        assertThat(parser.parseBalanceStatement("VM-HDFCBK", "Get a loan of Rs 5,00,000 today! T&C apply")).isNull()
    }
}
