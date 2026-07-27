package app.clearsms.domain.parser

import app.clearsms.domain.model.AccountType
import app.clearsms.domain.model.MerchantCategory
import app.clearsms.domain.model.ReminderType
import app.clearsms.domain.model.TransactionType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

/**
 * Regression fixtures for transaction titles, merchant/bank attribution and
 * false-transaction shapes. Every fixture is a real user message (structure
 * preserved; digits altered).
 */
class TransactionAttributionFixesTest {
    private val parser = TransactionParser()
    private val reminderParser = ReminderParser()

    // region standalone merchant line + foreign currency (Axis card spend)

    private val axisUsdSpend =
        "Spent USD 40.95\nAxis Bank Card no. XX5106\n20-07-26 07:40:29 IST\nUBER * PEND\nAvl Limit: INR 286368.5"

    @Test
    fun `card spend with merchant on its own line titles the merchant not the bank`() {
        val result = parser.parse("AXISBK", axisUsdSpend)
        assertThat(result).isNotNull()
        assertThat(result!!.merchantName).isEqualTo("Uber")
        assertThat(result.type).isEqualTo(TransactionType.DEBIT)
        assertThat(result.accountLast4).isEqualTo("5106")
        assertThat(result.bankName).isEqualTo("Axis Bank")
        assertThat(result.merchantCategory).isEqualTo(MerchantCategory.TRANSPORTATION)
    }

    @Test
    fun `foreign currency spend keeps the foreign amount and records the currency`() {
        val result = parser.parse("AXISBK", axisUsdSpend)
        // The amount is the USD figure — NOT the INR available limit.
        assertThat(result!!.amount).isEqualTo(40.95)
        // "Avl Limit" is credit headroom, never a balance.
        assertThat(result.balance).isNull()
        assertThat(parser.foreignCurrency(axisUsdSpend)).isEqualTo("USD")
    }

    @Test
    fun `domestic transactions report no foreign currency`() {
        assertThat(
            parser.foreignCurrency("Rs.250.00 debited from A/c XX9805 to VPA merchant@okicici on 20-07-26."),
        ).isNull()
    }

    // endregion

    // region Info-field descriptor (HDFC RD)

    private val hdfcRdDebit =
        "UPDATE: INR 13,000.00 debited from HDFC Bank XX8709 on 16-JUL-26. " +
            "Info: XXXXXXXXXX6894- RD Installment-Jul 2026. Avl bal:INR 1,07,721.74"

    @Test
    fun `rd debit is titled from the Info field not the bank`() {
        val result = parser.parse("HDFCBK", hdfcRdDebit)
        assertThat(result).isNotNull()
        assertThat(result!!.merchantName).isEqualTo("RD Installment")
        assertThat(result.type).isEqualTo(TransactionType.DEBIT)
        assertThat(result.amount).isEqualTo(13000.0)
        assertThat(result.bankName).isEqualTo("HDFC Bank")
        assertThat(result.accountLast4).isEqualTo("8709")
        // A deposit contribution, not a purchase.
        assertThat(result.merchantCategory).isEqualTo(MerchantCategory.INVESTMENT)
    }

    @Test
    fun `info field that is only a reference never becomes a title`() {
        val result =
            parser.parse(
                "HDFCBK",
                "INR 500.00 debited from HDFC Bank XX8709 on 16-JUL-26. Info: 5199123456789. Avl bal:INR 1,000.00",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.merchantName).isNull()
    }

    // endregion

    // region future-tense premiums are reminders, not transactions (ICICI Pru)

    private val iciciPruPremium =
        "ICICIPru policy H6501092 is due. Premium of Rs. 1531 will be deducted on due date 13-Jul-26 " +
            "as per standing instructions. You may choose to pay in advance now at https://s.ipru.co/xYz12"

    @Test
    fun `future tense premium deduction is not a transaction`() {
        assertThat(parser.parse("ICICIPRU", iciciPruPremium)).isNull()
    }

    @Test
    fun `future tense premium notice is an insurance reminder with amount and due date`() {
        val reminder = reminderParser.parse("ICICIPRU", iciciPruPremium)
        assertThat(reminder).isNotNull()
        assertThat(reminder!!.type).isEqualTo(ReminderType.INSURANCE)
        assertThat(reminder.totalDue).isEqualTo(1531.0)
        assertThat(reminder.dueDate).isEqualTo(LocalDate.of(2026, 7, 13))
    }

    @Test
    fun `will be debited and shall be charged never produce transactions`() {
        assertThat(
            parser.parse("HDFCBK", "Rs.999.00 will be debited from A/c XX1234 on 05-08-26 towards your subscription."),
        ).isNull()
        assertThat(
            parser.parse("HDFCBK", "An amount of Rs.499 shall be deducted from your account on the due date."),
        ).isNull()
    }

    @Test
    fun `completed deduction still parses as a transaction`() {
        val result = parser.parse("HDFCBK", "Rs.999.00 was deducted from A/c XX1234 on 05-07-26 towards your subscription.")
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(TransactionType.DEBIT)
    }

    @Test
    fun `merchant heuristic never captures from a url`() {
        val result =
            parser.parse(
                "HDFCBK",
                "Rs.500.00 debited from A/c XX1234. Pay your next bill in advance at https://pay.example.co/abc",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.merchantName).isNull()
    }

    // endregion

    // region issuer vs merchant precedence

    private val pluxeeSpend =
        "Rs. 570.00 was spent from Reimbursement Wallet linked to your Pluxee Card xx2703 on 21-12-2023 21:41:41 " +
            "at Paytm. Txn no. 446270200086. Avl bal is Rs. 6301.84. Pluxee"

    @Test
    fun `pluxee wallet spend keeps pluxee as issuer and paytm as merchant`() {
        val result = parser.parse("VD-Pluxee", pluxeeSpend)
        assertThat(result).isNotNull()
        assertThat(result!!.bankName).isEqualTo("Pluxee")
        assertThat(result.merchantName).isEqualTo("Paytm")
        assertThat(result.accountLast4).isEqualTo("2703")
        assertThat(result.accountType).isEqualTo(AccountType.WALLET)
        assertThat(result.amount).isEqualTo(570.0)
    }

    private val credPayment =
        "Payment of INR 20,846.56 was received for card number 4315-81XX-XXXX-4001 on 31-May-2021 and " +
            "you have earned 20,847 CRED coins. Simply download the app to claim them, order id."

    @Test
    fun `cred payment attaches to the card by last4 and cred is never the issuer`() {
        val result = parser.parse("CREDCL", credPayment)
        assertThat(result).isNotNull()
        // Grouped-card format yields the LAST group, not the BIN.
        assertThat(result!!.accountLast4).isEqualTo("4001")
        assertThat(result.type).isEqualTo(TransactionType.CREDIT)
        assertThat(result.amount).isEqualTo(20846.56)
        assertThat(result.accountType).isEqualTo(AccountType.CREDIT_CARD)
        // CRED is a payment channel: never an account's bank.
        assertThat(result.bankName).isNull()
    }

    @Test
    fun `grouped card number extraction handles mask variants`() {
        val result = parser.parse("ICICIB", "Payment of Rs 100 received for card number 4315-81XX-XXXX-9944 on 01-01-26")
        assertThat(result!!.accountLast4).isEqualTo("9944")
    }

    private val flipkartRefund =
        "Refund Processed: The refund of Rs.3100.0 for Mi 4X 80 cm HD Ready LED Smart TV is successfully " +
            "processed to your account ending with ***********709 and it will be credited by Feb 12, 2021. " +
            "In case of any concern, contact us with refund reference number: 104116248046."

    @Test
    fun `flipkart refund makes flipkart the merchant and the account issuerless`() {
        val result = parser.parse("FLPKRT", flipkartRefund)
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(TransactionType.CREDIT)
        assertThat(result.amount).isEqualTo(3100.0)
        assertThat(result.accountLast4).isEqualTo("709")
        // Flipkart identifies the merchant, not a bank.
        assertThat(result.merchantName).isEqualTo("Flipkart")
        assertThat(result.bankName).isNull()
    }

    // endregion

    // region account-creation guardrail (SenderNameResolver.isPlausibleIssuer)

    @Test
    fun `banks and wallets are plausible issuers`() {
        assertThat(SenderNameResolver.isPlausibleIssuer("HDFC Bank")).isTrue()
        assertThat(SenderNameResolver.isPlausibleIssuer("Pluxee")).isTrue()
        assertThat(SenderNameResolver.isPlausibleIssuer("Paytm Payments Bank")).isTrue()
        // Uncurated but self-evidently a bank.
        assertThat(SenderNameResolver.isPlausibleIssuer("AUBANK")).isTrue()
    }

    @Test
    fun `channels merchants and ecommerce brands are not issuers`() {
        assertThat(SenderNameResolver.isPlausibleIssuer("CRED")).isFalse()
        assertThat(SenderNameResolver.isPlausibleIssuer("Flipkart")).isFalse()
        assertThat(SenderNameResolver.isPlausibleIssuer("Airtel")).isFalse()
        assertThat(SenderNameResolver.isPlausibleIssuer("JUSPAY")).isFalse()
        assertThat(SenderNameResolver.isPlausibleIssuer("")).isFalse()
        assertThat(SenderNameResolver.isPlausibleIssuer(null)).isFalse()
    }

    @Test
    fun `an unknown issuer named in a card phrase is plausible`() {
        assertThat(
            SenderNameResolver.isPlausibleIssuer("Slice", "Rs.100 spent on your Slice card xx1234"),
        ).isTrue()
        assertThat(
            SenderNameResolver.isPlausibleIssuer("Slice", "Rs.100 cashback earned with Slice this week"),
        ).isFalse()
    }

    // endregion

    // region statement notices are not transactions

    private val iciciStatement =
        "ICICI Bank Credit Card XX4001 Statement is sent to pr******it@example.com. " +
            "Total of Rs 11,710.55 or minimum of Rs 590.00 is due by 07-AUG-26."

    @Test
    fun `credit card statement notice is not a transaction`() {
        assertThat(parser.isStatementNotice(iciciStatement)).isTrue()
        assertThat(parser.parse("ICICIB", iciciStatement)).isNull()
    }

    @Test
    fun `statement notice stays a credit card reminder`() {
        val reminder = reminderParser.parse("ICICIB", iciciStatement)
        assertThat(reminder).isNotNull()
        assertThat(reminder!!.type).isEqualTo(ReminderType.CREDIT_CARD)
        assertThat(reminder.totalDue).isEqualTo(11710.55)
        assertThat(reminder.minDue).isEqualTo(590.0)
        assertThat(reminder.dueDate).isEqualTo(LocalDate.of(2026, 8, 7))
    }

    @Test
    fun `other statement shapes are excluded from transactions too`() {
        assertThat(
            parser.parse(
                "AXISBK",
                "Axis Bank Credit Card XX1234 Statement is generated. Total amt due Rs 4,255.00, min due Rs 212.75 by 05-08-26.",
            ),
        ).isNull()
        assertThat(
            parser.parse(
                "HDFCBK",
                "E-statement of your HDFC Bank Credit Card XX5523 has been mailed to your registered email id.",
            ),
        ).isNull()
    }

    @Test
    fun `real debit mentioning a statement elsewhere still parses`() {
        val result =
            parser.parse(
                "HDFCBK",
                "Rs.2,000.00 debited from A/c XX1234 towards CARD PAYMENT on 05-07-26. Avl bal Rs.9,000.00",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.amount).isEqualTo(2000.0)
    }

    // endregion
}
