package app.clearsms.domain.parser

import app.clearsms.R
import app.clearsms.data.rules.RuleDocument
import app.clearsms.data.rules.RuleEngine
import app.clearsms.domain.categorizer.ContactLookup
import app.clearsms.domain.categorizer.MessageCategorizer
import app.clearsms.domain.categorizer.SenderIdLookup
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SenderInfo
import app.clearsms.domain.model.SubCategory
import app.clearsms.domain.model.TransactionType
import app.clearsms.notification.TransactionNotifier
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File

/**
 * UPI collect / payment-request defect: "You've received an IPO request from
 * <company> for up to Rs.X. Click to accept." (a real device shape, OCR'd,
 * with synthetic company and amount) used to parse as a CREDIT of ₹X - money
 * that never moved. A collect request must derive NO transaction, categorize
 * as an IMPORTANT bank alert (the mandate-notice treatment, unified via
 * [TransactionParser.isPaymentRequestNotice]), and any parsed notification
 * must use the informational blue UNSIGNED treatment with the requested
 * amount - never the green "+ ₹" credit style. The APPROVED request's later
 * execution arrives as its own "debited" message and must still parse.
 */
class CollectRequestTest {
    private val parser = TransactionParser()

    private fun categorizer(senderIdLookup: SenderIdLookup = SenderIdLookup { null }) =
        MessageCategorizer(
            ruleEngine = RuleEngine(),
            senderIdLookup = senderIdLookup,
            contactLookup = ContactLookup { false },
        )

    // region the OCR'd device fixture (synthetic company/amount)

    @Test
    fun `phonepe IPO collect request derives no transaction and exposes the requested amount`() {
        assertThat(parser.parse("PHONPE", OCR_FIXTURE)).isNull()
        assertThat(parser.isCollectRequest(OCR_FIXTURE)).isTrue()
        assertThat(parser.requestedAmount(OCR_FIXTURE)).isEqualTo(14807.0)
    }

    @Test
    fun `phonepe IPO collect request categorizes as an important bank alert`() {
        // Unknown sender: content fallback path.
        val fallback = categorizer().categorize("PHONPE", OCR_FIXTURE, emptyList(), emptyList())
        assertThat(fallback.category).isEqualTo(Category.IMPORTANT)
        assertThat(fallback.subCategory).isEqualTo(SubCategory.BANK_ALERT)
        // Directory-promotional sender: the payment-request carve-out lifts it.
        val promoDirectory = SenderIdLookup { SenderInfo("PhonePe", Category.PROMOTIONAL, null) }
        val viaDirectory = categorizer(promoDirectory).categorize("PHONPE", OCR_FIXTURE, emptyList(), emptyList())
        assertThat(viaDirectory.category).isEqualTo(Category.IMPORTANT)
        assertThat(viaDirectory.subCategory).isEqualTo(SubCategory.BANK_ALERT)
    }

    @Test
    fun `bundled rules classify the collect request as an important bank alert`() {
        val json = Json { ignoreUnknownKeys = true }
        val asset =
            sequenceOf(
                File("src/main/assets/default_rules.json"),
                File("app/src/main/assets/default_rules.json"),
            ).first(File::exists)
        val rules = json.decodeFromString(RuleDocument.serializer(), asset.readText()).rules
        val result = RuleEngine().evaluate(rules, "PHONPE", OCR_FIXTURE)
        assertThat(result).isNotNull()
        assertThat(result!!.category).isEqualTo(Category.IMPORTANT)
        assertThat(result.subCategory).isEqualTo(SubCategory.BANK_ALERT)
    }

    @Test
    fun `collect request notification content is blue and unsigned with the requested amount`() {
        val content =
            TransactionNotifier.buildContent(
                details = mapOf("requested_amount" to "14807.0"),
                balanceUpdateLabel = "Balance update",
                accountFormat = "A/c %1\$s",
                requestLabel = "Payment request",
            )
        assertThat(content).isNotNull()
        assertThat(content!!.kind).isEqualTo(TransactionNotifier.Content.Kind.BALANCE)
        assertThat(content.title).isEqualTo("₹14,807")
        assertThat(content.title).doesNotContain("+")
        assertThat(content.title).doesNotContain("−")
        assertThat(content.text).isEqualTo("Payment request")
        assertThat(TransactionNotifier.amountColorRes(content.kind)).isEqualTo(R.color.notif_amount_balance)
    }

    // endregion

    // region siblings

    @Test
    fun `bank-side collect request derives no transaction and is a bank alert`() {
        val body = "RAMESH KUMAR has requested Rs.500.00 from your account. Approve in your UPI app by 14-08-26."
        assertThat(parser.parse("VM-HDFCBK", body)).isNull()
        val result = categorizer().categorize("VM-HDFCBK", body, emptyList(), emptyList())
        assertThat(result.category).isEqualTo(Category.IMPORTANT)
        assertThat(result.subCategory).isEqualTo(SubCategory.BANK_ALERT)
    }

    @Test
    fun `IPO mandate blocked confirmation derives no transaction and is a bank alert`() {
        val body = "Rs.14807.00 blocked for IPO of EXAMPLE TRANSMISSION LIMITED via UPI mandate. UMN 1a2b@okhdfc."
        assertThat(parser.parse("VM-HDFCBK", body)).isNull()
        val result = categorizer().categorize("VM-HDFCBK", body, emptyList(), emptyList())
        assertThat(result.category).isEqualTo(Category.IMPORTANT)
        assertThat(result.subCategory).isEqualTo(SubCategory.BANK_ALERT)
    }

    @Test
    fun `declined collect request derives no transaction`() {
        val body = "You have declined the payment request of Rs.500.00 from RAMESH KUMAR."
        assertThat(parser.isCollectRequest(body)).isTrue()
        assertThat(parser.parse("PHONPE", body)).isNull()
    }

    // endregion

    // region near-misses: real money movement must keep parsing

    @Test
    fun `executed IPO mandate debit still parses as a debit`() {
        val body = "Amount blocked for IPO of EXAMPLE TRANSMISSION LIMITED has been debited: Rs.14807.00 debited from A/c XX1234."
        assertThat(parser.isCollectRequest(body)).isFalse()
        val tx = parser.parse("VM-HDFCBK", body)
        assertThat(tx).isNotNull()
        assertThat(tx!!.type).isEqualTo(TransactionType.DEBIT)
        assertThat(tx.amount).isEqualTo(14807.0)
    }

    @Test
    fun `a genuine credit is unaffected by the collect request guard`() {
        val body = "Rs.2,000.00 received in your A/c XX1234 from RAMESH KUMAR via UPI. Ref 424817849668."
        assertThat(parser.isCollectRequest(body)).isFalse()
        val tx = parser.parse("VM-HDFCBK", body)
        assertThat(tx).isNotNull()
        assertThat(tx!!.type).isEqualTo(TransactionType.CREDIT)
        assertThat(tx.amount).isEqualTo(2000.0)
    }

    @Test
    fun `mandate execution debit is not vetoed by the unified guard`() {
        val body = "Rs.649.00 debited from A/c XX1234 for Netflix via UPI mandate."
        assertThat(parser.isPaymentRequestNotice(body)).isFalse()
        val tx = parser.parse("VM-HDFCBK", body)
        assertThat(tx).isNotNull()
        assertThat(tx!!.type).isEqualTo(TransactionType.DEBIT)
    }

    // endregion

    private companion object {
        /** OCR'd from a real device screenshot; company and amount synthetic. */
        const val OCR_FIXTURE =
            "You've received an IPO request from EXAMPLE TRANSMISSION LIMITED for up to Rs.14807. " +
                "Click to accept. https://phone.pe/PHONPE/x8m9nrb3"
    }
}
