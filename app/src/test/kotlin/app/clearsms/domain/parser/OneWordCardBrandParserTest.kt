package app.clearsms.domain.parser

import app.clearsms.domain.model.AccountType
import app.clearsms.domain.model.TransactionType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Indian card issuers brand the product as ONE word - BOBCARD, SBICARD,
 * ONECARD - so "your BOBCARD ending 1234" carries no standalone "card"
 * token. The old card regex demanded a word boundary before "card" and
 * never matched, so the account fell through to SAVINGS and a credit card
 * was filed under bank accounts. The card regex now accepts a brand prefix
 * glued to "card" - the same one-word shape the reminder evidence table
 * already uses for "Statement for BOBCARD **1234".
 *
 * All senders, tails, amounts and merchants are synthetic.
 */
class OneWordCardBrandParserTest {
    private val parser = TransactionParser()

    private val spend =
        "ALERT: INR 1,462.35 is spent on your BOBCARD ending 5081 at Green Leaf Organics on 19-08-2026. " +
            "Available credit limit is Rs 1,48,537.65, Current outstanding is Rs 0.00. Not you? Call 18002090 (toll-free)"

    @Test
    fun `a BOBCARD spend is a debit on a credit card`() {
        val result = parser.parse("VM-BOBCRD", spend)
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(TransactionType.DEBIT)
        assertThat(result.amount).isEqualTo(1462.35)
        assertThat(result.accountType).isEqualTo(AccountType.CREDIT_CARD)
        assertThat(result.accountLast4).isEqualTo("5081")
        assertThat(result.bankName).isEqualTo("Bank of Baroda")
        assertThat(result.merchantName).isEqualTo("Green Leaf Organics")
    }

    @Test
    fun `the available credit limit populates the card's limit and never the balance or amount`() {
        val result = parser.parse("VM-BOBCRD", spend)
        assertThat(result!!.availableLimit).isEqualTo(148537.65)
        assertThat(result.balance).isNull()
        assertThat(result.amount).isEqualTo(1462.35)
    }

    @Test
    fun `a BOBCARD payment received is a credit on the same card`() {
        val result =
            parser.parse(
                "VM-BOBCRD",
                "Update: Payment of Rs 1462.35 received for your BOBCARD ending 5081 on 2026-09-02. " +
                    "Thank you. Know more: bobcard.io/Pymt",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(TransactionType.CREDIT)
        assertThat(result.amount).isEqualTo(1462.35)
        assertThat(result.accountType).isEqualTo(AccountType.CREDIT_CARD)
        assertThat(result.accountLast4).isEqualTo("5081")
        assertThat(result.bankName).isEqualTo("Bank of Baroda")
    }

    @Test
    fun `a BOBCARD statement is a bill, never a spend`() {
        val result =
            parser.parse(
                "VM-BOBCRD",
                "Statement for BOBCARD **5081 for AUG26 is generated. Pay Total: Rs 1462.35 or Min Due: Rs 200 by 13-09-26. " +
                    "View/Download Statement on Mobile App. Know more: bobcard.io/Pymt.",
            )
        assertThat(result).isNull()
    }

    @Test
    fun `the masked-tail statement shape reads as a card`() {
        assertThat(parser.accountTypeOf("Statement for BOBCARD **5081 for AUG26 is generated"))
            .isEqualTo(AccountType.CREDIT_CARD)
        assertThat(parser.accountTypeOf("View your BOBCARD **5081 Aug26 bill at bobcard.io/Bill"))
            .isEqualTo(AccountType.CREDIT_CARD)
    }

    @Test
    fun `other one-word card brands type as cards too`() {
        val sbi =
            parser.parse(
                "VM-SBICRD",
                "Rs.2,350.00 spent on your SBICARD ending 4410 at BIG BAZAAR on 12-09-26. Avl Limit Rs.97,650.00",
            )
        assertThat(sbi).isNotNull()
        assertThat(sbi!!.accountType).isEqualTo(AccountType.CREDIT_CARD)
        assertThat(sbi.accountLast4).isEqualTo("4410")

        val one = parser.parse("VM-ONECRD", "Rs 899.00 spent on your ONECARD **7731 at ZOMATO on 12-09-26")
        assertThat(one).isNotNull()
        assertThat(one!!.accountType).isEqualTo(AccountType.CREDIT_CARD)
        assertThat(one.accountLast4).isEqualTo("7731")
    }

    @Test
    fun `a BOB bank account debit that merely mentions BOBCARD stays a bank account`() {
        val result =
            parser.parse(
                "VM-BOBTXN",
                "Rs.1,200.00 debited from your A/c XX6640 on 11-09-26 to VPA grocer@upi. Avl Bal Rs.8,400.00. " +
                    "Apply for BOBCARD today, T&C apply -BOB",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.accountType).isEqualTo(AccountType.SAVINGS)
        assertThat(result.accountLast4).isEqualTo("6640")
        assertThat(result.bankName).isEqualTo("Bank of Baroda")
    }

    @Test
    fun `a two-word card phrase is unchanged`() {
        assertThat(parser.accountTypeOf("Rs.500 spent on your HDFC Bank Credit Card ending 1234"))
            .isEqualTo(AccountType.CREDIT_CARD)
        assertThat(parser.accountTypeOf("Rs.500 debited from A/c XX1234")).isEqualTo(AccountType.SAVINGS)
    }
}
