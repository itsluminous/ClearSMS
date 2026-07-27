package app.clearsms.data.rules

import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SubCategory
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File

/**
 * Coverage tests for the second wave of sender-specific rules (ICICI UPI
 * shapes, Meesho/Shadowfax/XpressBees logistics, Unity SFB, HSBC, Amex,
 * SBI Card, NPS and marketing-blast displacement). Every message here is
 * SYNTHETIC — shapes only, no real corpus text. Each representative rule is
 * asserted to match its intended shape with the documented capture order
 * and to reject a near-miss, and the two confirmed misfires (offer blasts
 * hitting the delivery and scam fallbacks) are pinned as fixed.
 */
class DeviceTwoCoverageRulesTest {
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

    // ------------------------------------------------------------------ ICICI

    @Test
    fun `icici upi credit captures account amount payer and reference in order`() {
        val result =
            evaluate(
                "AD-ICICIT-S",
                "Dear Customer, Acct XX123 is credited with Rs 5000.00 on 12-Jul-26 " +
                    "from SAMPLE PAYER. UPI:516912345678-ICICI Bank.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("icici-upi-credit-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.TRANSACTION)
        assertThat(result?.extracted?.get("account_last4")).isEqualTo("123")
        assertThat(result?.extracted?.get("amount")).isEqualTo("5000.00")
        assertThat(result?.extracted?.get("merchant")).isEqualTo("SAMPLE PAYER")
        assertThat(result?.extracted?.get("reference")).isEqualTo("516912345678")
        assertThat(result?.extracted?.get("type")).isEqualTo("credit")
    }

    @Test
    fun `icici upi mandate debit captures amount account and payee`() {
        val result =
            evaluate(
                "AD-ICICIT-S",
                "Rs 199.00 debited from ICICI Bank Savings Account XX123 on 14-Jul-26 " +
                    "towards SAMPLE SERVICE for UPI Mandate AutoPay Retrieval Ref No.512212345678",
            )
        assertThat(result?.matchedRuleId).isEqualTo("icici-mandate-debit-01")
        assertThat(result?.extracted?.get("amount")).isEqualTo("199.00")
        assertThat(result?.extracted?.get("account_last4")).isEqualTo("123")
        assertThat(result?.extracted?.get("merchant")).isEqualTo("SAMPLE SERVICE")
        assertThat(result?.extracted?.get("type")).isEqualTo("debit")
    }

    @Test
    fun `icici narrated debit captures balance after the info segment`() {
        val result =
            evaluate(
                "AD-ICICIT-S",
                "ICICI Bank Acc XX123 debited Rs. 1,00,000.00 on 15-Jul-26 InfoTRF TO FD no.." +
                    "Avl Bal Rs. 45,000.00.To dispute call 18001234 or SMS BLOCK 611 to 9215676766",
            )
        assertThat(result?.matchedRuleId).isEqualTo("icici-debit-info-01")
        assertThat(result?.extracted?.get("account_last4")).isEqualTo("123")
        assertThat(result?.extracted?.get("amount")).isEqualTo("1,00,000.00")
        assertThat(result?.extracted?.get("balance")).isEqualTo("45,000.00")
        assertThat(result?.extracted?.get("type")).isEqualTo("debit")
    }

    @Test
    fun `icici neft credit captures reference and available balance`() {
        val result =
            evaluate(
                "AD-ICICIT-S",
                "ICICI Bank Account XX123 credited:Rs. 1,20,000.00 on 14-Jul-26. " +
                    "Info NEFT-SAMP0011223-PAYER. Available Balance is Rs. 2,00,000.00.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("icici-credit-info-01")
        assertThat(result?.extracted?.get("account_last4")).isEqualTo("123")
        assertThat(result?.extracted?.get("amount")).isEqualTo("1,20,000.00")
        assertThat(result?.extracted?.get("reference")).isEqualTo("NEFT-SAMP0011223-PAYER")
        assertThat(result?.extracted?.get("balance")).isEqualTo("2,00,000.00")
        assertThat(result?.extracted?.get("type")).isEqualTo("credit")
    }

    @Test
    fun `icici upcoming autopay debit is a bill reminder not a transaction`() {
        val result =
            evaluate(
                "AD-ICICIT-S",
                "ICICI Bank SAVINGS Account XX123 will be debited for Rs 59.00 on " +
                    "15-Jul-26 towards Autopay for StreamingSvc. To modify visit the app.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("icici-autopay-due-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.BILL)
        assertThat(result?.extracted?.get("account_last4")).isEqualTo("123")
        assertThat(result?.extracted?.get("amount")).isEqualTo("59.00")
        assertThat(result?.extracted?.get("due_date")).isEqualTo("15-Jul-26")
        assertThat(result?.extracted?.get("merchant")).isEqualTo("Autopay for StreamingSvc")
    }

    @Test
    fun `icici mandate debit rejects a credit wording near-miss`() {
        val result =
            evaluate(
                "AD-ICICIT-S",
                "Rs 199.00 credited to ICICI Bank Savings Account XX123 on 14-Jul-26 " +
                    "towards SAMPLE SERVICE for UPI Mandate",
            )
        assertThat(result?.matchedRuleId).isNotEqualTo("icici-mandate-debit-01")
    }

    // ------------------------------------------------- other banks and cards

    @Test
    fun `canara service charge debit resolves last4 of a full account number`() {
        val result =
            evaluate(
                "CANBNK",
                "An amount of INR 250.00 has been DEBITED to your account 1234567890 on " +
                    "12/07/2026 towards services charges. Total Avail.bal INR 320.00. - Canara Bank",
            )
        assertThat(result?.matchedRuleId).isEqualTo("canara-amount-debit-01")
        assertThat(result?.extracted?.get("amount")).isEqualTo("250.00")
        assertThat(result?.extracted?.get("account_last4")).isEqualTo("7890")
        assertThat(result?.extracted?.get("type")).isEqualTo("debit")
    }

    @Test
    fun `unity neft debit captures reference and balance in order`() {
        val result =
            evaluate(
                "VD-UNTYBA-S",
                "Rs 25000.5 debited from your A/c no xxxxxxxx1234 for NEFT transaction on " +
                    "12-07-26 . NEFT Ref no- 123456789012 Avl Bal:Rs 5.5. Not done by you? " +
                    "Contact 18005551111- Unity Bank.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("unity-neft-debit-01")
        assertThat(result?.extracted?.get("amount")).isEqualTo("25000.5")
        assertThat(result?.extracted?.get("account_last4")).isEqualTo("1234")
        assertThat(result?.extracted?.get("reference")).isEqualTo("123456789012")
        assertThat(result?.extracted?.get("balance")).isEqualTo("5.5")
        assertThat(result?.extracted?.get("type")).isEqualTo("debit")
    }

    @Test
    fun `unity credit with balance captures all three amounts`() {
        val result =
            evaluate(
                "VD-UNTYBA-S",
                "Rs 150000.00 credited to your a/c xxxx5678 on 12-Oct-2025. " +
                    "Avl Bal Rs. 150200.00. Unity SFB.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("unity-credit-bal-01")
        assertThat(result?.extracted?.get("amount")).isEqualTo("150000.00")
        assertThat(result?.extracted?.get("account_last4")).isEqualTo("5678")
        assertThat(result?.extracted?.get("balance")).isEqualTo("150200.00")
    }

    @Test
    fun `unity fixed deposit creation is a fixed deposit alert`() {
        val result =
            evaluate(
                "VD-UNTYBA-S",
                "Hi, your FD for Rs 200000.00 has been successfully created. Maturity date " +
                    "is 12-May-2027 and Maturity Amt is Rs 215000.00. Unity SFB.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("unity-fd-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.FIXED_DEPOSIT)
        assertThat(result?.extracted?.get("amount")).isEqualTo("200000.00")
    }

    @Test
    fun `hsbc credit interest captures narration and balance`() {
        val result =
            evaluate(
                "TM-HSBCIN-S",
                "HSBC: Dear Customer, your HSBC A/c 123-456***-789 has been credited with " +
                    "INR 5.50+ on 30JUN as CREDIT INTEREST . Your available Bal is 1200.00 .",
            )
        assertThat(result?.matchedRuleId).isEqualTo("hsbc-credit-01")
        assertThat(result?.extracted?.get("account_last4")).isEqualTo("789")
        assertThat(result?.extracted?.get("amount")).isEqualTo("5.50")
        assertThat(result?.extracted?.get("merchant")).isEqualTo("CREDIT INTEREST")
        assertThat(result?.extracted?.get("balance")).isEqualTo("1200.00")
    }

    @Test
    fun `sbi imps credit captures account amount and reference`() {
        val result =
            evaluate(
                "AD-SBIINB-S",
                "Dear Customer, Your a/c no. 123456789012 is credited by Rs.4500.00 on " +
                    "10-07-26 by a/c linked to mobile (IMPS Ref no 519912345678). " +
                    "If not done by you, call 18001234.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("sbi-imps-credit-01")
        assertThat(result?.extracted?.get("account_last4")).isEqualTo("9012")
        assertThat(result?.extracted?.get("amount")).isEqualTo("4500.00")
        assertThat(result?.extracted?.get("reference")).isEqualTo("519912345678")
    }

    @Test
    fun `sbi deposit by transfer captures balance`() {
        val result =
            evaluate(
                "BW-CBSSBI-S",
                "Your A/C 12345678901 Credited INR 25,000.00 on 12/07/26 -Deposit by " +
                    "transfer from SAMPLE. Avl Bal INR 1,00,000.00-SBI",
            )
        assertThat(result?.matchedRuleId).isEqualTo("sbi-credit-transfer-01")
        assertThat(result?.extracted?.get("account_last4")).isEqualTo("8901")
        assertThat(result?.extracted?.get("amount")).isEqualTo("25,000.00")
        assertThat(result?.extracted?.get("balance")).isEqualTo("1,00,000.00")
    }

    @Test
    fun `sbi card e-statement with dues captures totals and payable date`() {
        val result =
            evaluate(
                "JM-MYSBIC-S",
                "E-statement of SBI Credit Card ending XX22 dated 05/07/2026 has been " +
                    "mailed. If not received, SMS ENRS to 5676791. Total Amt Due Rs 4521; " +
                    "Min Amt Due Rs 226; Payable by 25/07/2026. Click example to pay your bill",
            )
        assertThat(result?.matchedRuleId).isEqualTo("sbi-estatement-due-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.BILL)
        assertThat(result?.extracted?.get("account_last4")).isEqualTo("22")
        assertThat(result?.extracted?.get("total_due")).isEqualTo("4521")
        assertThat(result?.extracted?.get("min_due")).isEqualTo("226")
        assertThat(result?.extracted?.get("due_date")).isEqualTo("25/07/2026")
    }

    @Test
    fun `sbi internet banking login otp is an otp`() {
        val result =
            evaluate(
                "AX-SBIINB-S",
                "123456 is OTP to login to SBI Internet banking(Personal) . Do not share with anyone. -SBI",
            )
        assertThat(result?.matchedRuleId).isEqualTo("sbi-otp-login-01")
        assertThat(result?.category).isEqualTo(Category.OTP)
        assertThat(result?.extracted?.get("otp_code")).isEqualTo("123456")
    }

    @Test
    fun `amex statement captures card total due and date`() {
        val result =
            evaluate(
                "VD-MYAMEX-S",
                "Dear Customer, your statement for AMEX Corporate Card **********12345 has " +
                    "been generated. Total payment of Rs.5.00 is due by 12/08/26.Please ignore " +
                    "this msg if already paid.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("amex-stmt-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.BILL)
        assertThat(result?.extracted?.get("account_last4")).isEqualTo("2345")
        assertThat(result?.extracted?.get("total_due")).isEqualTo("5.00")
        assertThat(result?.extracted?.get("due_date")).isEqualTo("12/08/26")
    }

    @Test
    fun `hdfc debit mandate alert captures merchant token`() {
        val result =
            evaluate(
                "VM-HDFCBK-S",
                "Debit Alert! Your HDFC Bank A/c xx1234 has been debited Rs 4999 on " +
                    "14th Jul' 2026 via Debit Mandate for SAMPLEPAY_TESTMERCHANT_123",
            )
        assertThat(result?.matchedRuleId).isEqualTo("hdfc-mandate-debit-01")
        assertThat(result?.extracted?.get("account_last4")).isEqualTo("1234")
        assertThat(result?.extracted?.get("amount")).isEqualTo("4999")
        assertThat(result?.extracted?.get("merchant")).isEqualTo("SAMPLEPAY_TESTMERCHANT_123")
        assertThat(result?.extracted?.get("type")).isEqualTo("debit")
    }

    @Test
    fun `hdfc ach deposit captures payer as merchant`() {
        val result =
            evaluate(
                "VM-HDFCBK-S",
                "Deposit Alert! INR 45.10 received in HDFC Bank A/C No 12345678901234 via " +
                    "ACH credit Mode from SAMPLE TECHNOLOGIES",
            )
        assertThat(result?.matchedRuleId).isEqualTo("hdfc-ach-credit-01")
        assertThat(result?.extracted?.get("amount")).isEqualTo("45.10")
        assertThat(result?.extracted?.get("account_last4")).isEqualTo("1234")
        assertThat(result?.extracted?.get("merchant")).isEqualTo("SAMPLE TECHNOLOGIES")
        assertThat(result?.extracted?.get("type")).isEqualTo("credit")
    }

    @Test
    fun `hdfc ipin regeneration otp is an otp`() {
        val result =
            evaluate(
                "VM-HDFCBK-S",
                "123456 is your OTP to regenerate IPIN to access your HDFC Bank A/c. NEVER share OTP",
            )
        assertThat(result?.matchedRuleId).isEqualTo("hdfc-otp-ipin-01")
        assertThat(result?.category).isEqualTo(Category.OTP)
        assertThat(result?.extracted?.get("otp_code")).isEqualTo("123456")
    }

    // ------------------------------------------------------------- logistics

    @Test
    fun `meesho return pickup otp captures the code`() {
        val result =
            evaluate(
                "JM-MEESUP-S",
                "Return Pickup: Share OTP 1234 to pickup your return order with AWB VLR12345678 -Meesho",
            )
        assertThat(result?.matchedRuleId).isEqualTo("meesho-otp-01")
        assertThat(result?.category).isEqualTo(Category.OTP)
        assertThat(result?.extracted?.get("otp_code")).isEqualTo("1234")
    }

    @Test
    fun `meesho refund is a credit transaction`() {
        val result =
            evaluate(
                "JM-MESHO-S",
                "Refund for Sample Item of Rs.230 is successfully processed to your Original Payment Source. Meesho",
            )
        assertThat(result?.matchedRuleId).isEqualTo("meesho-refund-01")
        assertThat(result?.extracted?.get("amount")).isEqualTo("230")
        assertThat(result?.extracted?.get("type")).isEqualTo("credit")
    }

    @Test
    fun `meesho refund rule rejects a not-yet-processed near-miss`() {
        val result =
            evaluate(
                "JM-MESHO-S",
                "Refund for Sample Item of Rs.230 will be initiated after pickup. Meesho",
            )
        assertThat(result?.matchedRuleId).isNotEqualTo("meesho-refund-01")
    }

    @Test
    fun `meesho delivered message is a delivery update`() {
        val result =
            evaluate(
                "JM-MEESHO-S",
                "Yay! Sample item & other items have been successfully delivered! Please " +
                    "take a minute to share your delivery experience on our app. Meesho",
            )
        assertThat(result?.matchedRuleId).isEqualTo("meesho-delivery-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.DELIVERY)
    }

    @Test
    fun `shadowfax rider pin is an otp`() {
        val result =
            evaluate(
                "VD-SFXDEL-S",
                "Delivery Code: Share Pin 1234 with rider to accept delivery of Sample " +
                    "order from Meesho. Get help @ https://tracker.example -Shadowfax",
            )
        assertThat(result?.matchedRuleId).isEqualTo("sfx-code-01")
        assertThat(result?.category).isEqualTo(Category.OTP)
        assertThat(result?.extracted?.get("otp_code")).isEqualTo("1234")
    }

    @Test
    fun `shadowfax picked up captures the awb`() {
        val result =
            evaluate(
                "VD-SFXREV-S",
                "PICKED SUCCESSFULLY Sample item from Meesho AWB R12345678901FPL. " +
                    "Visit https://tracker.example for acknowledgement slip - Shadowfax",
            )
        assertThat(result?.matchedRuleId).isEqualTo("sfx-picked-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.DELIVERY)
        assertThat(result?.extracted?.get("tracking_id")).isEqualTo("R12345678901FPL")
    }

    @Test
    fun `xpressbees out for delivery code is an otp`() {
        val result =
            evaluate(
                "VD-XPBEES-S",
                "Sample item (Cou.. from Meesho,AWB:15012345678901 is out for delivery," +
                    "plz share delivery code:123456 with executive,For query call:18001234 - Xpressbees",
            )
        assertThat(result?.matchedRuleId).isEqualTo("xpbees-delivery-code-01")
        assertThat(result?.category).isEqualTo(Category.OTP)
        assertThat(result?.extracted?.get("otp_code")).isEqualTo("123456")
    }

    // ------------------------------------------------------- misfire fixes

    @Test
    fun `bankbazaar offer blast is promotional not a delivery update`() {
        val result =
            evaluate(
                "BV-BBANKC-S",
                "Hi Customer, DELIVERED TO INBOX: -Sample Bank Credit Card OFFER -View " +
                    "details via safe link TnC apply. BankBazaar https://example.test/x",
            )
        assertThat(result?.matchedRuleId).isEqualTo("bankbazaar-offer-01")
        assertThat(result?.category).isEqualTo(Category.PROMOTIONAL)
        assertThat(result?.subCategory).isEqualTo(SubCategory.OFFER)
    }

    @Test
    fun `techgig contest promo is promotional not a scam`() {
        val result =
            evaluate(
                "VM-TECHGG-S",
                "Dear Participants, These free badges can help you win prizes and access " +
                    "to other courses - Team TechGig https://tinyurl.com/example1",
            )
        assertThat(result?.matchedRuleId).isEqualTo("techgig-promo-01")
        assertThat(result?.category).isEqualTo(Category.PROMOTIONAL)
        assertThat(result?.subCategory).isEqualTo(SubCategory.OFFER)
    }

    @Test
    fun `scam safety net still fires for prize bait with a shortened url`() {
        val result =
            evaluate(
                "BP-RANDOM-S",
                "Congratulations! You have won a lottery prize. Claim now https://tinyurl.com/win-big",
            )
        assertThat(result?.matchedRuleId).isEqualTo("generic-scam-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.SCAM)
    }

    // ------------------------------------------------- reminders and promos

    @Test
    fun `nobrokerhood payment due is a bill reminder`() {
        val result =
            evaluate(
                "VM-NBHOOD-S",
                "Payment due: Rs.550 for A-101 against latest bill. Pay at " +
                    "https://example.test/x. Ignore if paid - NoBrokerHood",
            )
        assertThat(result?.matchedRuleId).isEqualTo("nbhood-invoice-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.BILL)
        assertThat(result?.extracted?.get("amount")).isEqualTo("550")
    }

    @Test
    fun `airtel pack expiry from a shortcode is a recharge reminder`() {
        val result =
            evaluate(
                "650001",
                "Your Airtel pack on 9812345678 is about to expire! Recharge now with " +
                    "Rs3599 to stay connected.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("airtel-pack-expiry-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isEqualTo(SubCategory.RECHARGE)
    }

    @Test
    fun `airtel shortcode marketing blast is promotional`() {
        val result =
            evaluate(
                "650025",
                "Catch every match this season! Stream it LIVE with a new Rs.398 Airtel " +
                    "pack. Click https://example.test/recharge to recharge.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("airtel-marketing-01")
        assertThat(result?.category).isEqualTo(Category.PROMOTIONAL)
    }

    @Test
    fun `nps login otp is an otp`() {
        val result =
            evaluate(
                "AD-KFNCRA-S",
                "OTP for online Login under NPS is 123456 valid for next 30 minutes only. " +
                    "If not requested, contact Nodal office - KFNCRA",
            )
        assertThat(result?.matchedRuleId).isEqualTo("kfncra-otp-01")
        assertThat(result?.category).isEqualTo(Category.OTP)
        assertThat(result?.extracted?.get("otp_code")).isEqualTo("123456")
    }

    @Test
    fun `digilocker otp matches the LOCKER header`() {
        val result =
            evaluate(
                "AD-LOCKER-S",
                "123456 is your OTP to access DigiLocker. OTP is confidential and valid " +
                    "for 10 minutes. For security reasons, DO NOT share this OTP with anyone.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("digilocker-otp-01")
        assertThat(result?.extracted?.get("otp_code")).isEqualTo("123456")
    }

    @Test
    fun `ministry of home affairs advisory is a government alert`() {
        val result =
            evaluate(
                "AX-MHAI4C-S",
                "I4C, Ministry of Home Affairs alerts against online shopping frauds, " +
                    "verify platform, beware of tempting offers. Call 1930 if fall victim",
            )
        assertThat(result?.matchedRuleId).isEqualTo("mha-cyber-advisory-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isEqualTo(SubCategory.GOVERNMENT)
    }

    @Test
    fun `jpmc emergency contact verification is important`() {
        val result =
            evaluate(
                "56161875",
                "JPMC Alert: This message is to verify the Firm's ability to contact you " +
                    "in an emergency. Please acknowledge promptly to avoid further messages.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("jpmc-alert-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
    }

    @Test
    fun `real estate blast from an unknown header is caught by the generic net`() {
        val result =
            evaluate(
                "BZ-ESTATE-S",
                "Premium 2 & 3 BHK apartments at just Rs. 60 Lacs onwards, best deal " +
                    "before launch. Call Now: 9800000000",
            )
        assertThat(result?.matchedRuleId).isEqualTo("generic-realestate-promo-01")
        assertThat(result?.category).isEqualTo(Category.PROMOTIONAL)
    }

    @Test
    fun `icici pru premium due captures date then amount`() {
        val result =
            evaluate(
                "JD-ICICIP-S",
                "Dear valued customer, premium due on 20-Aug-26 for your ICICIPru policy " +
                    "no. H1234567 of Rs. 3122 will be deducted as per standing instructions.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("icicipru-premium-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.BILL)
        assertThat(result?.extracted?.get("due_date")).isEqualTo("20-Aug-26")
        assertThat(result?.extracted?.get("amount")).isEqualTo("3122")
    }
}
