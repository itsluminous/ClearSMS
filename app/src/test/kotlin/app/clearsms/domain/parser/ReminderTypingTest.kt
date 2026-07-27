package app.clearsms.domain.parser

import app.clearsms.domain.model.ReminderType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Regression suite for reminder TYPE classification (see
 * [ReminderTypeClassifier]). Every body below is a synthetic replica of a
 * real message SHAPE observed on-device (numbers invented, digits changed) —
 * one test per confirmed misclassification plus regressions for every shape
 * verified correct, so a future rule tweak cannot silently reintroduce the
 * loose-keyword bugs (bare "premium" -> INSURANCE, bare "plan" ->
 * SUBSCRIPTION).
 */
class ReminderTypingTest {
    private val parser = ReminderParser()

    // region confirmed mistags, now fixed

    @Test
    fun `OTT tier named Premium is a SUBSCRIPTION not INSURANCE - valid till`() {
        val result =
            parser.parse(
                "AD-LIVCNF",
                "Yay! Your LIV Premium subscription is now active. Order ID : 123456789 Your subscription is valid till 15 Oct 2026. - SonyLIV",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.SUBSCRIPTION)
    }

    @Test
    fun `OTT tier named Premium is a SUBSCRIPTION not INSURANCE - auto-renew`() {
        val result =
            parser.parse(
                "AX-LIVCNF-S",
                "Yay! Your LIV Premium subscription is now active on 9812345678. Order ID: 123456789. Your subscription will auto-renew on 15 Dec 2026 -Sony LIV",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.SUBSCRIPTION)
    }

    @Test
    fun `generated telecom bill is a BILL not SUBSCRIPTION`() {
        val result =
            parser.parse(
                "AX-AIRBIL",
                "Hi Prakash, Bill for your Airtel Mobile 9812345678 dated 15-MAY-2026 has been generated. " +
                    "Amount to be paid: Rs 649.00 Due Date: 04-06-2026 This month's charges: Rs 611.00 (inclusive of tax) Details on the app.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.OTHER)
    }

    @Test
    fun `monthly operator bill is a BILL not SUBSCRIPTION`() {
        val result =
            parser.parse(
                "ViCARE",
                "Your Sep'26 bill for Vi no. 9812345678 of Rs.560.65 due on 15/10/2026, is sent to your registered email ID. " +
                    "Our new Whatsapp assistant is now LIVE! For any billing/payment queries say hi.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.OTHER)
    }

    @Test
    fun `investment marketing pitch produces no reminder even with premium and due date`() {
        assertThat(
            parser.parse(
                "AX-ABCABS",
                "Dear Prakash Kumar, With rising capital markets, investment in ULIP's can reap benefits in the long term. " +
                    "It offers flexibility in managing funds, switching from debt, balanced and equity funds. " +
                    "Your premium of Rs. 250000 on ABSLI Wealth Aspire with Policy No 123456789 due on 15-08-2026. " +
                    "Click here to pay now. T&C Apply. ABSLI",
            ),
        ).isNull()
    }

    // endregion

    // region additional mistags found in the audit, now fixed

    @Test
    fun `voucher expiry from a credit card program produces no reminder`() {
        assertThat(
            parser.parse(
                "GYFTRR",
                "Dear PRAKASH, Here's your Quarterly Domestic Airport Lounge Voucher from Tata Neu Infinity HDFC Bank Credit Card. " +
                    "Voucher Code: XHDFC12345 Expiry Date: 30 Jun 2026 For any help, call us.",
            ),
        ).isNull()
    }

    @Test
    fun `insurance premium charged to a credit card is INSURANCE not CREDIT_CARD`() {
        val result =
            parser.parse(
                "ICICIP",
                "Dear valued customer, premium due on 15-Jul-26 for your ICICIPru policy no. H1234567 for Rs. 5000 " +
                    "will be deducted from your credit card as per standing instructions. Kindly ignore if paid. T&C apply.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.INSURANCE)
    }

    // endregion

    // region verified-correct shapes must not regress

    @Test
    fun `card statement with total and min due stays CREDIT_CARD`() {
        val result =
            parser.parse(
                "HDFCBK",
                "HDFC Bank Credit Card XX4400 Statement: Total due amt: Rs.987.65 Min due amt: Rs.100.00 Due date: 05-08-2026. Pay on the app.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.CREDIT_CARD)
    }

    @Test
    fun `payment for card is due shape stays CREDIT_CARD`() {
        val result =
            parser.parse(
                "AXISBK",
                "Payment of INR 23456.75 for Axis Bank Credit Card no. XX5678 is due on 12-06-26 with minimum amount due of INR 1173. Ignore if paid.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.CREDIT_CARD)
    }

    @Test
    fun `rd installment stays DEPOSIT`() {
        val result =
            parser.parse(
                "VD-HDFCBK",
                "RD Installment Due! Amount INR 12,345.00 Due on 05-AUG-26 HDFC Bank RD 98765 Check RD statement on the MobileBanking App",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.DEPOSIT)
    }

    @Test
    fun `monthly instalment for RD stays DEPOSIT`() {
        val result =
            parser.parse(
                "VK-HDFCBK",
                "Reminder! Your monthly instalment of INR 10,000.00 for your RD 98765 is due on 05-SEP-26. Check RD statement on the app.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.DEPOSIT)
    }

    @Test
    fun `debit card EMI loan statement stays EMI`() {
        val result =
            parser.parse(
                "VK-HDFCBK",
                "Generated - E-Statement for HDFC Bank Debit Card EMI Loan 1234567890123456 EMI Due date: 05/DEC/2026 EMI DUE : 4500 Kindly pay overdue, if any",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.EMI)
    }

    @Test
    fun `broadband bill for month on account stays BILL`() {
        val result =
            parser.parse(
                "CP-ACTGRP",
                "Dear Patron, Your bill for AUG-26 on A/C 123456789012 is INR 1234.56. Due date:15-AUG-26. Pay @ the portal.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.OTHER)
    }

    @Test
    fun `payment is due for your mobile shape stays BILL`() {
        val result =
            parser.parse(
                "AN-AIRBIL",
                "Hi, a payment of Rs. 649 is due on 15-SEP-26 for your Airtel Mobile 9812345678 , please pay before the due date to continue services.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.OTHER)
    }

    @Test
    fun `premium with standing instructions and policy number stays INSURANCE`() {
        val result =
            parser.parse(
                "VK-ICICIP",
                "Premium due on 15-May-2026 for your ICICIPru policy ICICI Pru iProtect Smart policy no H1234567 for Rs. 5000 " +
                    "shall be charged as per standing instructions. T&C",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.INSURANCE)
    }

    @Test
    fun `premium towards policy number stays INSURANCE`() {
        val result =
            parser.parse(
                "VD-ABCABS",
                "Kumar Prakash, your premium of Rs. 250000 towards your ABSLI Policy No. 123456789 is due on 15-08-2026. " +
                    "Use our digital modes to pay from the comfort of your home. Ignore if already paid. T&C",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.INSURANCE)
    }

    @Test
    fun `renewal premium for life insurance policy stays INSURANCE`() {
        val result =
            parser.parse(
                "VK-CAMSIR-S",
                "Renewal premium for TATA AIA Life Insurance policy no. C123456789 is due on 15-MAY-2026. -Insurance Repository",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.INSURANCE)
    }

    @Test
    fun `subscription renewal due stays SUBSCRIPTION`() {
        val result =
            parser.parse(
                "AX-PYTMBK",
                "Your subscription renewal with Google of Rs.1950 is due on 15 Apr 2026. Add money to your wallet now.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.SUBSCRIPTION)
    }

    @Test
    fun `membership activation valid till stays SUBSCRIPTION`() {
        val result =
            parser.parse(
                "AX-OYORMS",
                "Hi Prakash, Welcome to the Wizard Blue tier! Your membership has been activated and is valid till 15-01-2027. Enjoy exciting perks.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.SUBSCRIPTION)
    }

    // endregion

    // region brand-tier "premium" false positives

    @Test
    fun `youtube premium plan renewal is SUBSCRIPTION`() {
        val result =
            parser.parse(
                "GOOGLE",
                "Your YouTube Premium plan is due for renewal. Pay by 05-08-2026 to continue watching ad-free.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.SUBSCRIPTION)
    }

    @Test
    fun `spotify premium subscription expiry is SUBSCRIPTION`() {
        val result =
            parser.parse(
                "SPOTFY",
                "Your Spotify Premium subscription is valid till 01 Sep 2026. Renew to keep listening without interruptions.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.SUBSCRIPTION)
    }

    @Test
    fun `amazon prime membership renewal is SUBSCRIPTION`() {
        val result =
            parser.parse(
                "AMAZON",
                "Your Amazon Prime membership will auto-renew on 12-09-2026 for Rs. 1499. Update your payment method if needed.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.SUBSCRIPTION)
    }

    // endregion

    // region disqualifiers and determinism

    @Test
    fun `bill evidence beats a stray plan keyword`() {
        val result =
            parser.parse(
                "AX-AIRBIL",
                "Hi Prakash, Bill for your Airtel Mobile 9812345678 dated 15-MAY-2026 has been generated. " +
                    "Amount to be paid: Rs 649.00 Due Date: 04-06-2026. Your plan renews monthly.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.OTHER)
    }

    @Test
    fun `policy evidence beats a stray subscription keyword`() {
        val result =
            parser.parse(
                "VK-ICICIP",
                "Premium due on 15-May-2026 for your ICICIPru policy no. H1234567 for Rs. 4500. Manage your e-statement subscription anytime.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(ReminderType.INSURANCE)
    }

    @Test
    fun `classification is deterministic for the same body`() {
        val sender = "AX-AIRBIL"
        val body =
            "Hi Prakash, Bill for your Airtel Mobile 9812345678 dated 15-MAY-2026 has been generated. " +
                "Amount to be paid: Rs 649.00 Due Date: 04-06-2026. Your plan renews monthly."
        val types = (1..50).map { parser.parse(sender, body)?.type }.toSet()
        assertThat(types).containsExactly(ReminderType.OTHER)
    }

    // endregion
}
