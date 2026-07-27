package app.clearsms.domain.parser

import app.clearsms.domain.model.AccountType
import app.clearsms.domain.model.MerchantCategory
import app.clearsms.domain.model.TransactionType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TransactionParserTest {
    private val parser = TransactionParser()

    @Test
    fun `hdfc sent from account debit`() {
        val result =
            parser.parse(
                "VM-HDFCBK-S",
                "Sent Rs.500.00 From HDFC Bank A/C x1234 To SWIGGY On 12/07/26 Ref 519912345678 Not You? Call 18002586161",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.amount).isEqualTo(500.0)
        assertThat(result.type).isEqualTo(TransactionType.DEBIT)
        assertThat(result.accountLast4).isEqualTo("1234")
        assertThat(result.merchantName).isEqualTo("SWIGGY")
        assertThat(result.bankName).isEqualTo("HDFC Bank")
        assertThat(result.referenceNumber).isEqualTo("519912345678")
        assertThat(result.merchantCategory).isEqualTo(MerchantCategory.FOOD)
    }

    @Test
    fun `icici upi debit`() {
        val result =
            parser.parse(
                "AD-ICICIB",
                "ICICI Bank Acct XX823 debited for Rs 4,500.00 on 15-Jul-26; PRIYA SHARMA credited. UPI:521912345678. Call 18002662 for dispute.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.amount).isEqualTo(4500.0)
        assertThat(result.type).isEqualTo(TransactionType.DEBIT)
        assertThat(result.accountLast4).isEqualTo("823")
        assertThat(result.bankName).isEqualTo("ICICI Bank")
        assertThat(result.merchantCategory).isEqualTo(MerchantCategory.TRANSFER)
    }

    @Test
    fun `sbi neft credit with balance`() {
        val result =
            parser.parse(
                "SBIINB",
                "Dear Customer, INR 25,000.00 credited to your A/c No XX4321 on 01/07/26 by NEFT Ref no CMS123456789. Avl Bal INR 45,230.50-SBI",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.amount).isEqualTo(25000.0)
        assertThat(result.type).isEqualTo(TransactionType.CREDIT)
        assertThat(result.accountLast4).isEqualTo("4321")
        assertThat(result.balance).isEqualTo(45230.50)
        assertThat(result.referenceNumber).isEqualTo("CMS123456789")
        assertThat(result.bankName).isEqualTo("SBI")
    }

    @Test
    fun `axis card spend`() {
        val result =
            parser.parse(
                "AX-AXISBK",
                "Spent Card no. XX5678 INR 1,299.00 12-07-26 19:20:11 AMAZON Avl Lmt INR 98,701.00 SMS BLOCK 5678 to 919951860002 - Axis Bank",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.amount).isEqualTo(1299.0)
        assertThat(result.type).isEqualTo(TransactionType.DEBIT)
        assertThat(result.accountLast4).isEqualTo("5678")
        assertThat(result.accountType).isEqualTo(AccountType.CREDIT_CARD)
        assertThat(result.bankName).isEqualTo("Axis Bank")
        assertThat(result.merchantCategory).isEqualTo(MerchantCategory.SHOPPING)
    }

    @Test
    fun `upi debit to vpa`() {
        val result =
            parser.parse(
                "VK-ICICIT",
                "Rs.250.00 debited from A/c XX9805 to VPA merchant@okicici on 20-07-26. Ref No 020520123456. Avl Bal Rs.5,000.25 - ICICI Bank.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.amount).isEqualTo(250.0)
        assertThat(result.type).isEqualTo(TransactionType.DEBIT)
        assertThat(result.merchantName).isEqualTo("merchant@okicici")
        assertThat(result.accountLast4).isEqualTo("9805")
        assertThat(result.balance).isEqualTo(5000.25)
        assertThat(result.merchantCategory).isEqualTo(MerchantCategory.TRANSFER)
    }

    @Test
    fun `credit card payment received`() {
        val result =
            parser.parse(
                "KOTAKB",
                "Payment of Rs.10,000.00 received on your Kotak Credit Card xx4400 on 18-07-26. Available limit Rs.90,000.00.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.amount).isEqualTo(10000.0)
        assertThat(result.type).isEqualTo(TransactionType.CREDIT)
        assertThat(result.accountLast4).isEqualTo("4400")
        assertThat(result.accountType).isEqualTo(AccountType.CREDIT_CARD)
        assertThat(result.bankName).isEqualTo("Kotak Mahindra Bank")
    }

    @Test
    fun `atm withdrawal`() {
        val result =
            parser.parse(
                "HDFCBK",
                "Rs.5000 withdrawn from HDFC Bank ATM from A/c XX1234 on 22-07-26. Avl Bal Rs.32,450.75",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.amount).isEqualTo(5000.0)
        assertThat(result.type).isEqualTo(TransactionType.DEBIT)
        assertThat(result.balance).isEqualTo(32450.75)
    }

    @Test
    fun `salary credit with indian comma grouping`() {
        val result =
            parser.parse(
                "SBIBNK",
                "Your A/c XX0987 is credited with Rs.85,000.00 on 01-07-26 (Salary for Jun). Avl Bal: Rs.1,02,340.00",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.amount).isEqualTo(85000.0)
        assertThat(result.type).isEqualTo(TransactionType.CREDIT)
        assertThat(result.balance).isEqualTo(102340.0)
    }

    @Test
    fun `uber maps to transportation`() {
        val result = parser.parse("HDFCBK", "Rs.230.00 paid to UBER India via UPI from A/c XX1111 on 12-07-26")
        assertThat(result).isNotNull()
        assertThat(result!!.merchantName).isEqualTo("UBER India")
        assertThat(result.merchantCategory).isEqualTo(MerchantCategory.TRANSPORTATION)
    }

    @Test
    fun `netflix maps to entertainment`() {
        val result = parser.parse("ICICIB", "Rs.649.00 debited from A/c XX2222 towards NETFLIX subscription on 01-07-26")
        assertThat(result).isNotNull()
        assertThat(result!!.merchantName).isEqualTo("NETFLIX subscription")
        assertThat(result.merchantCategory).isEqualTo(MerchantCategory.ENTERTAINMENT)
    }

    @Test
    fun `apollo maps to hospital`() {
        val result = parser.parse("AXISBK", "Rs.850.00 paid at APOLLO PHARMACY on 12-07-26 from A/c XX3333")
        assertThat(result).isNotNull()
        assertThat(result!!.merchantName).isEqualTo("APOLLO PHARMACY")
        assertThat(result.merchantCategory).isEqualTo(MerchantCategory.HOSPITAL)
    }

    @Test
    fun `zerodha maps to investment`() {
        val result = parser.parse("HDFCBK", "Rs.10,000.00 debited from A/c XX4444 towards ZERODHA mutual fund SIP")
        assertThat(result).isNotNull()
        assertThat(result!!.merchantCategory).isEqualTo(MerchantCategory.INVESTMENT)
    }

    @Test
    fun `electricity maps to utility bill`() {
        val result = parser.parse("PYTMPB", "Rs.1,540.00 paid towards ELECTRICITY bill for consumer 123456 via UPI")
        assertThat(result).isNotNull()
        assertThat(result!!.merchantCategory).isEqualTo(MerchantCategory.UTILITY_BILL)
    }

    @Test
    fun `otp message is not a transaction`() {
        val result = parser.parse("HDFCBK", "Your OTP is 482910 for txn of Rs.4,500 at Amazon. Do not share it with anyone.")
        assertThat(result).isNull()
    }

    @Test
    fun `promotional offer is not a transaction`() {
        val result = parser.parse("VM-OFFERS", "Flat 40% off up to Rs.100 on your first order. Order now! T&C apply.")
        assertThat(result).isNull()
    }

    @Test
    fun `credit without amount is not a transaction`() {
        val result = parser.parse("REWARDS", "Your account has been credited with reward points for your last purchase.")
        assertThat(result).isNull()
    }

    @Test
    fun `balance alert is not a transaction`() {
        val result = parser.parse("SBIBNK", "Avl Bal in A/c XX1234 is Rs.5,000.00 as on 20-07-26. Missed call to 09223766666 for balance.")
        assertThat(result).isNull()
    }
}
