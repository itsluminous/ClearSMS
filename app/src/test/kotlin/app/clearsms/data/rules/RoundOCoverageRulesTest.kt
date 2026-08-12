package app.clearsms.data.rules

import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SubCategory
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File

/**
 * Fixture tests for the round-O coverage rules: sender-specific rules that
 * displace generic-* fallbacks and cover recurring A2P shapes found in a
 * real-corpus audit (telecom, banks, cards, wallets, couriers, workplace
 * paging, travel). Every message here is SYNTHETIC - patterns were derived
 * from message *shapes* only; each family asserts one match (with captures
 * where the rule extracts) and one near-miss.
 */
class RoundOCoverageRulesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val engine = RuleEngine()

    private val rules: List<RuleDefinition> by lazy {
        val file =
            listOf(
                File("src/main/assets/default_rules.json"),
                File("app/src/main/assets/default_rules.json"),
            ).firstOrNull { it.exists() }
        checkNotNull(file) { "default_rules.json not found" }
        json.decodeFromString(RuleDocument.serializer(), file.readText()).rules
    }

    private fun evaluate(
        sender: String,
        body: String,
    ) = engine.evaluate(rules, sender, body)

    // region telecom

    @Test
    fun `vi international roaming notice from VIROAM`() {
        val result =
            evaluate(
                "VM-VIROAM-S",
                "Hello! For any assistance you can now call our 24x7 Vi Customer Care number absolutely FREE while roaming internationally. Happy to Help!",
            )
        assertThat(result?.matchedRuleId).isEqualTo("vi-roaming-care-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
    }

    @Test
    fun `vi survey prompt on the 59823 shortcode`() {
        val result =
            evaluate(
                "59823",
                "Where are you facing this issue? Reply with a number between 1 to 3 corresponding to the options below 1 - Indoor Coverage 2 - Outdoor Coverage 3 - No Coverage",
            )
        assertThat(result?.matchedRuleId).isEqualTo("vi-survey-01")
        assertThat(result?.category).isEqualTo(Category.PROMOTIONAL)
    }

    @Test
    fun `vi survey rule needs the dedicated shortcode`() {
        val result = evaluate("VM-OTHRCO-S", "Text a score from 0 to 10 for our service")
        assertThat(result?.matchedRuleId).isNotEqualTo("vi-survey-01")
    }

    @Test
    fun `vi recharged receipt is a recharge not a promo`() {
        val result =
            evaluate(
                "VM-VICARE-S",
                "Rs349 recharged! Enjoy Unlimited Calls + 2GB Data + 100 SMS. Validity 28 Days. Click example.com to know more.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("vi-recharged-02")
        assertThat(result?.subCategory).isEqualTo(SubCategory.RECHARGE)
        assertThat(result?.extracted?.get("amount")).isEqualTo("349")
    }

    @Test
    fun `airtel prepaid recharge success with order id`() {
        val result =
            evaluate(
                "AX-AIRINF-S",
                "Hi, Your Prepaid recharge of Rs. 599.0 is success against Order Id 1234567890123456789. Please keep the Order ID for future reference.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("airtel-recharge-processed-02")
        assertThat(result?.subCategory).isEqualTo(SubCategory.RECHARGE)
        assertThat(result?.extracted?.get("amount")).isEqualTo("599.0")
    }

    @Test
    fun `airtel retailer recharge receipt with MRP`() {
        val result =
            evaluate(
                "AX-ERECHARGE",
                "Recharge done on 01-01-2026 10:00PM,MRP:Rs19.00,GST 18% payable by Company/Distributor/Retailer,TransID 123456789.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("airtel-retailer-recharge-01")
        assertThat(result?.extracted?.get("amount")).isEqualTo("19.00")
    }

    @Test
    fun `airtel failed payment is not a transaction`() {
        val result =
            evaluate(
                "AX-AIRINF-S",
                "Hi, payment of Rs. 599.0 has failed for your Airtel Mobile 9800000000. Any amount, if debited will be refunded within a day.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("airtel-payment-failed-01")
        assertThat(result?.subCategory).isNotEqualTo(SubCategory.TRANSACTION)
    }

    // endregion

    // region banks and cards

    @Test
    fun `citi service reply on the 52484 shortcode`() {
        val result =
            evaluate(
                "52484",
                "Request unsuccessful, the combination of Mobile Number, Card Number entered is incorrect - Citi",
            )
        assertThat(result?.matchedRuleId).isEqualTo("citi-service-reply-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.BANK_ALERT)
    }

    @Test
    fun `citi card delivered by courier`() {
        val result =
            evaluate(
                "VM-CITIBA-S",
                "Your Citi card ending 9099 has been delivered via BLUE DART. Create APIN/IPIN at example.com",
            )
        assertThat(result?.matchedRuleId).isEqualTo("citi-card-delivered-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.DELIVERY)
    }

    @Test
    fun `citi refund initiated captures amount and card`() {
        val result =
            evaluate(
                "VM-CITIBA-S",
                "Dear Customer, refund of Rs. 250.00 has been initiated for your Citi Credit Card ending 9099 on 01-DEC-25 by SAMPLE MERCHANT.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("citi-cc-refund-01")
        assertThat(result?.extracted?.get("amount")).isEqualTo("250.00")
        assertThat(result?.extracted?.get("account_last4")).isEqualTo("9099")
        assertThat(result?.extracted?.get("type")).isEqualTo("credit")
    }

    @Test
    fun `citi refund rule does not fire for another issuer`() {
        val result =
            evaluate(
                "VM-OTRBNK-S",
                "Refund of Rs. 250.00 has been initiated for your Citi Credit Card ending 9099.",
            )
        assertThat(result?.matchedRuleId).isNotEqualTo("citi-cc-refund-01")
    }

    @Test
    fun `bobcard otp keyword first`() {
        val result =
            evaluate(
                "VM-BOBCRD-S",
                "OTP is 4821 for MOBILE NO. CHANGE via BOBCARD mobile app. Valid for 5 mins. NEVER SHARE OTP for your security.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("bobcard-otp-01")
        assertThat(result?.category).isEqualTo(Category.OTP)
        assertThat(result?.extracted?.get("otp_code")).isEqualTo("4821")
    }

    @Test
    fun `bobcard otp digits first`() {
        val result =
            evaluate(
                "VM-BOBCRD-S",
                "482123 is your OTP for Login to the BOBCARD webpage. Valid for 5 mins. Do Not share OTP with anyone.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("bobcard-otp-02")
        assertThat(result?.extracted?.get("otp_code")).isEqualTo("482123")
    }

    @Test
    fun `axis card transaction otp captures code and amount`() {
        val result =
            evaluate(
                "VM-AXISBK-S",
                "482123 is SECRET OTP for txn of INR 999.00 on Axis Bank card 4321XX at SAMPLE STORE on 01-01-26 10:00:00. OTP valid for 5 mins.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("axis-otp-txn-01")
        assertThat(result?.category).isEqualTo(Category.OTP)
        assertThat(result?.extracted?.get("otp_code")).isEqualTo("482123")
        assertThat(result?.extracted?.get("amount")).isEqualTo("999.00")
    }

    @Test
    fun `sbi card estatement notice is a bill`() {
        val result =
            evaluate(
                "VM-SBICRD-S",
                "E-stmt for your SBI Card ending with 9099 dated 05/08/2026 has been sent to your registered email ID. Total Amt Due Rs 1234.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("sbi-card-estmt-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.BILL)
        assertThat(result?.extracted?.get("account_last4")).isEqualTo("9099")
    }

    @Test
    fun `sbi card website login otp`() {
        val result =
            evaluate(
                "VM-SBICRD-S",
                "OTP to login to your account on SBI Card website is 482123. OTP is valid for one login attempt or 5 mins only.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("sbi-card-web-otp-01")
        assertThat(result?.extracted?.get("otp_code")).isEqualTo("482123")
    }

    @Test
    fun `yono web login otp from SBYONO`() {
        val result =
            evaluate(
                "VM-SBYONO-S",
                "482123 is the OTP to login to YONO Web (Internet Banking). Do not share with anyone.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("sbi-yono-otp-01")
        assertThat(result?.extracted?.get("otp_code")).isEqualTo("482123")
    }

    @Test
    fun `income tax refund credited via SBI`() {
        val result =
            evaluate(
                "VM-SBIBNK-S",
                "Dear Customer, For PAN XXXXX1234X, An IT Refund amount of Rs 12345 for AY-2025-26 has been CREDITED to your account.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("sbi-it-refund-01")
        assertThat(result?.extracted?.get("amount")).isEqualTo("12345")
        assertThat(result?.extracted?.get("type")).isEqualTo("credit")
    }

    @Test
    fun `sbi credit card dispatched`() {
        val result =
            evaluate(
                "VM-SBICRD-S",
                "Your SBI Credit Card has been dispatched to your residence address on 01-FEB-26 through BLUE DART Ref. No. EV123.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("sbi-card-dispatch-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.DELIVERY)
    }

    @Test
    fun `canara pos debit on card-linked account`() {
        val result =
            evaluate(
                "VM-CANBNK-S",
                "A/c XX9099 linked to card debited INR 1,05,000.00 on 01/08/26 POS txn. Avl Bal INR 25,000.00. To stop further txns call us.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("canara-pos-debit-01")
        assertThat(result?.extracted?.get("amount")).isEqualTo("1,05,000.00")
        assertThat(result?.extracted?.get("type")).isEqualTo("debit")
    }

    @Test
    fun `canara confirmation otp`() {
        val result =
            evaluate(
                "VM-CANBNK-S",
                "Your request has been initiated. Please confirm with OTP:482123. Do not disclose this OTP to anyone.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("canara-otp-confirm-01")
        assertThat(result?.extracted?.get("otp_code")).isEqualTo("482123")
    }

    @Test
    fun `idfc estatement with inline dues captures both amounts`() {
        val result =
            evaluate(
                "VM-IDFCFB-S",
                "The eStatement for your FIRST Wealth Credit Card is here. Total Amt Due: Rs. 5,432.10 Min Amt Due: Rs.271.61 Use your app to pay.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("idfc-first-estatement-02")
        assertThat(result?.extracted?.get("total_due")).isEqualTo("5,432.10")
        assertThat(result?.extracted?.get("min_due")).isEqualTo("271.61")
    }

    @Test
    fun `idfc rewards redemption is not flagged as scam`() {
        val result =
            evaluate(
                "VM-IDFCFR-S",
                "Congratulations! You've successfully redeemed Gift Card(s) worth Rs.500.0 from IDFC First Bank Rewards Portal using your IDFC First Credit Card. Check details at http://tinyurl.com/example",
            )
        assertThat(result?.matchedRuleId).isEqualTo("idfc-rewards-redeemed-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isNotEqualTo(SubCategory.SCAM)
    }

    @Test
    fun `prize bait with shortener still lands as scam`() {
        val result =
            evaluate(
                "VM-RNDMKT-S",
                "Congratulations! You have won a lucky draw prize. Claim now at tinyurl.com/claim-fast",
            )
        assertThat(result?.matchedRuleId).isEqualTo("generic-scam-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.SCAM)
    }

    @Test
    fun `hdfc received credit captures amount and account`() {
        val result =
            evaluate(
                "VM-HDFCBK-S",
                "Received! INR 5.00 in HDFC Bank A/c xx9099 On 01-08-26 For IMPS -Sample bank- 123456789012 Avl bal INR 1,00,000.00",
            )
        assertThat(result?.matchedRuleId).isEqualTo("hdfc-credit-received-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.TRANSACTION)
        assertThat(result?.extracted?.get("amount")).isEqualTo("5.00")
        assertThat(result?.extracted?.get("account_last4")).isEqualTo("9099")
        assertThat(result?.extracted?.get("type")).isEqualTo("credit")
    }

    @Test
    fun `hdfc smartpay bill paid captures biller and account`() {
        val result =
            evaluate(
                "VM-HDFCBK-S",
                "Bill Paid! SMPLMF Bill SMP1234567890 of Rs. 5000.00 paid on 01-Apr-2026 from HDFC Bank Account 9099 via SmartPay.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("hdfc-smartpay-02")
        assertThat(result?.extracted?.get("amount")).isEqualTo("5000.00")
        assertThat(result?.extracted?.get("account_last4")).isEqualTo("9099")
        assertThat(result?.extracted?.get("type")).isEqualTo("debit")
    }

    @Test
    fun `icici upcoming standing instruction debit is a bill not a transaction`() {
        val result =
            evaluate(
                "AD-ICICIB-S",
                "Dear Customer, your payment of INR 299.00 for SampleSub to be debited from your ICICI Bank Credit Card 9002, as per your standing instruction.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("icici-cc-autopay-notice-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.BILL)
        assertThat(result?.extracted?.get("amount")).isEqualTo("299.00")
        assertThat(result?.extracted?.get("account_last4")).isEqualTo("9002")
    }

    // endregion

    // region wallets

    @Test
    fun `amazon pay icici earnings credited to wallet balance`() {
        val result =
            evaluate(
                "+919800000000",
                "Dear Customer, earnings of Rs.123.45, for usage of Amazon Pay ICICI Bank Credit Card in the last billing cycle, has been credited to your Amazon Pay balance.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("amazonpay-icici-earnings-01")
        assertThat(result?.extracted?.get("amount")).isEqualTo("123.45")
        assertThat(result?.extracted?.get("type")).isEqualTo("credit")
    }

    @Test
    fun `amazon pay cashback added to balance`() {
        val result =
            evaluate(
                "+919800000000",
                "Cashback of Rs 25.00 for NoRush Cashback added to your Amazon Pay balance. Total balance: Rs 90.0.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("amazonpay-cashback-01")
        assertThat(result?.extracted?.get("amount")).isEqualTo("25.00")
    }

    @Test
    fun `cred automatic refund with rupee symbol`() {
        val result =
            evaluate(
                "VM-CREDIN-S",
                "A refund of \u20b975.00 has been automatically initiated to your bank account. It should be credited within 5 hours.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("cred-refund-01")
        assertThat(result?.extracted?.get("amount")).isEqualTo("75.00")
        assertThat(result?.extracted?.get("type")).isEqualTo("credit")
    }

    @Test
    fun `paytm account link otp notice has no code to extract`() {
        val result =
            evaluate(
                "VM-IPAYTM-S",
                "OTP to link your Paytm account on SampleShop is available on Paytm app. Click https://example.com/link",
            )
        assertThat(result?.matchedRuleId).isEqualTo("paytm-link-otp-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
    }

    // endregion

    // region government and health

    @Test
    fun `epfo password change otp with otp id`() {
        val result =
            evaluate(
                "VM-EPFOHO-S",
                "Dear Member, use OTP 482123 (OTP-ID: 123456) to change the password of UAN 100000000000. Do not share this OTP.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("epfo-otp-01")
        assertThat(result?.extracted?.get("otp_code")).isEqualTo("482123")
    }

    @Test
    fun `epfo member interface login otp`() {
        val result =
            evaluate(
                "VM-EPFOHO-S",
                "Dear Member (UAN : 1000 0000 0000), OTP to login to Member Interface is 482123 OTP-ID 1234. Do not share with anyone.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("epfo-login-otp-01")
        assertThat(result?.extracted?.get("otp_code")).isEqualTo("482123")
    }

    @Test
    fun `aadhaar pvc card handed to india post`() {
        val result =
            evaluate(
                "VM-ADHAAR-S",
                "Your Aadhaar PVC Card with SRN (S1234) has been handed over to India Post for delivery to your address.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("uidai-pvc-delivery-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.DELIVERY)
    }

    @Test
    fun `passport dispatched via speed post`() {
        val result =
            evaluate(
                "VM-TCSPSK-S",
                "BN1234: Passport No. A1234567 dispatched on 01/08/2026 and can be tracked using Speed Post Tracking No EA123.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("passport-dispatched-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.DELIVERY)
    }

    @Test
    fun `vfs visa application update`() {
        val result =
            evaluate(
                "VM-VFSSMS-S",
                "The processed visa application for GWF ref no. GWF123456789 was received at the Visa Application Centre.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("vfs-visa-update-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.GOVERNMENT)
    }

    @Test
    fun `narayana health lab report link`() {
        val result =
            evaluate(
                "VM-NCARE-S",
                "Dear patient, you can access your lab report using this link http://example.com/report - Narayana Health",
            )
        assertThat(result?.matchedRuleId).isEqualTo("nh-patient-link-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
    }

    // endregion

    // region ecommerce and couriers

    @Test
    fun `amazon installation status check with order id`() {
        val result =
            evaluate(
                "555662",
                "Hello, We're writing to check the installation status of your Amazon.in order# 123-1234567-1234567. Please let us know if you need assistance.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("amazon-install-status-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
    }

    @Test
    fun `amazon job application status change`() {
        val result =
            evaluate(
                "+919800000000",
                "The status of your Amazon job application has changed. Please check an email from us for details.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("amazon-job-application-01")
    }

    @Test
    fun `amazon return pickup cancelled`() {
        val result =
            evaluate(
                "+919800000000",
                "Pickup Cancelled: Your return, 123-1234567-1234567 was cancelled at the time of pickup. To initiate a return again, click here: https://amzn.in/d/example",
            )
        assertThat(result?.matchedRuleId).isEqualTo("amazon-return-cancelled-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.DELIVERY)
    }

    @Test
    fun `amazon service completed needs the amzn link`() {
        val matched =
            evaluate(
                "+919800000000",
                "Service TV Installation Service is completed as per technician. If not completed, report issue https://amzn.in/d/example within 6 hrs.",
            )
        assertThat(matched?.matchedRuleId).isEqualTo("amazon-service-completed-01")
        val nearMiss =
            evaluate(
                "+919800000000",
                "Service TV Installation Service is completed as per technician. Report issues on our website.",
            )
        assertThat(nearMiss?.matchedRuleId).isNotEqualTo("amazon-service-completed-01")
    }

    @Test
    fun `amazon order placed confirmation`() {
        val result =
            evaluate(
                "+919800000000",
                "Confirmed: Your Amazon.in order 123-1234567-1234567 is successfully placed & will reach you on 15-Aug between 6:00 PM to 10:00 PM.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("amazon-order-confirmed-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.DELIVERY)
    }

    @Test
    fun `plain order id without amazon context stays out of amazon rules`() {
        val result = evaluate("+919800000000", "Reference 123-1234567-1234567 noted, thanks!")
        assertThat(result?.matchedRuleId).isNotEqualTo("amazon-install-status-01")
    }

    @Test
    fun `croma order out for delivery`() {
        val result =
            evaluate(
                "VM-ECROMA-S",
                "Hi, Your SOA123456789012 is out for delivery! For updates, you can track it here https://www.croma.com/my-account/orders Rgds, Team Croma",
            )
        assertThat(result?.matchedRuleId).isEqualTo("croma-delivery-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.DELIVERY)
    }

    @Test
    fun `zipcare protection plan activation`() {
        val result =
            evaluate(
                "VM-ZIPCRE-S",
                "Dear Customer, your newly purchased Sample Cooler from Croma is now protected with ZipCare Protect - Advanced plan.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("zipcare-plan-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
    }

    @Test
    fun `vishal mega mart coupon is promotional`() {
        val result =
            evaluate(
                "VM-VISHM-S",
                "Congrats ! Here is your Vishal Mega Mart coupon worth Rs200 USECODE: SAMPLE1 Redeemable on shopping T&C",
            )
        assertThat(result?.matchedRuleId).isEqualTo("vishalmm-coupon-01")
        assertThat(result?.category).isEqualTo(Category.PROMOTIONAL)
    }

    @Test
    fun `shiprocket order shipped`() {
        val result =
            evaluate(
                "VM-SHPRKT-S",
                "Order Shipped: Hi, your order Sample Gift from Sample Store is on its way with SampleCourier.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("shiprocket-shipped-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.DELIVERY)
    }

    @Test
    fun `nimbuspost order shipped with awb`() {
        val result =
            evaluate(
                "VM-NIMPST-S",
                "Hi, Your order id 123-1234567 , AWB 123456789012345 from has been shipped via SAMPLECOURIER. To track, click here: https://example.com/track Thanks NimbusPost",
            )
        assertThat(result?.matchedRuleId).isEqualTo("nimbuspost-shipped-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.DELIVERY)
    }

    @Test
    fun `dtdc failed delivery attempt`() {
        val result =
            evaluate(
                "VM-DTDCCR-S",
                "DTDC D1234567 was not delivered as RECEIVER NOT AVAILABLE. Please validate by clicking example.com. DTDC doesn't seek any payment OTP.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("dtdc-delivery-failed-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.DELIVERY)
    }

    @Test
    fun `flipkart egift voucher order received`() {
        val result =
            evaluate(
                "VM-FLPKRT-S",
                "Flipkart Order Received: We have received your order for e-Gift Voucher worth R... with order id OD123456789012345678.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("flipkart-giftcard-order-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
    }

    // endregion

    // region utilities, work alerts and travel

    @Test
    fun `gail gas dpng invoice generated`() {
        val result =
            evaluate(
                "+919800000000",
                "Your Invoice for DPNG has been generated, to view click https://tinyurl.com/example Kindly pay by due date. GAIL Gas",
            )
        assertThat(result?.matchedRuleId).isEqualTo("gailgas-invoice-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.BILL)
    }

    @Test
    fun `everbridge mckalert emergency comms test`() {
        val result =
            evaluate(
                "+919800000000",
                "MckAlert: Test of the emergency communication system - Everbridge Reply with YES to confirm receipt.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("everbridge-alert-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
    }

    @Test
    fun `amazon alerting pager notification`() {
        val result =
            evaluate(
                "+919800000000",
                "Amazon Alerting: From: issues@example.com. Reply 123456 to acknowledge. Text HELP for help, STOP to opt-out.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("amazon-alerting-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
    }

    @Test
    fun `monitoring alert on the 503501 shortcode`() {
        val result =
            evaluate(
                "503501",
                "12345: CPU saturation - OPEN Problem P-1234567: CPU saturation on Host sample-host-01",
            )
        assertThat(result?.matchedRuleId).isNotNull()
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
    }

    @Test
    fun `monitoring rule needs its dedicated shortcode`() {
        val result = evaluate("VM-RNDMCO-S", "Dynatrace saturation outage failure monitor")
        assertThat(result?.matchedRuleId).isNotEqualTo("mss-monitor-alert-01")
    }

    @Test
    fun `vistara boarding pass link`() {
        val result =
            evaluate(
                "503501",
                "UK123 15/08 at 10:30 https://fly.airvistara.com/ssci/bp?j=SAMPLE&je=SAMPLE&lang=en",
            )
        assertThat(result?.matchedRuleId).isEqualTo("vistara-boarding-pass-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.TRAVEL)
    }

    @Test
    fun `tripsource flight delay update`() {
        val result =
            evaluate(
                "+12025550100",
                "TripSource: Your flight, XX 1234, has been delayed. It is now scheduled to depart at 9:15 pm.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("tripsource-flight-update-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.TRAVEL)
    }

    @Test
    fun `airline cancellation refund captures pnr and amount`() {
        val result =
            evaluate(
                "+919800000000",
                "Your flight PAT-BLR (PNR:ABCDEF) was cancelled by the airline. Refund of Rs. 4500.00 will appear in your original payment method.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("flight-cancelled-refund-01")
        assertThat(result?.extracted?.get("pnr")).isEqualTo("ABCDEF")
        assertThat(result?.extracted?.get("amount")).isEqualTo("4500.00")
        assertThat(result?.extracted?.get("type")).isEqualTo("credit")
    }

    // endregion
}
