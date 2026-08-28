package app.clearsms.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What counts as a tappable link in a message body. The traps matter more
 * than the hits here: an SMS inbox is mostly amounts, account tails, PNRs
 * and dates, and turning any of those into a link would be worse than
 * having no links at all. All bodies are synthetic.
 */
@RunWith(RobolectricTestRunner::class)
class BodyLinkFinderTest {
    private fun urls(body: String) = BodyLinkFinder.find(body).map { it.url }

    private fun texts(body: String) = BodyLinkFinder.find(body).map { it.text }

    @Test
    fun `a scheme-less host is linked over https`() {
        val body =
            "Dear Customer, Porter Partner has been allocated to your order. " +
                "You can reach out to them from here - porter.in/rd/2ece6fcccd"

        assertThat(texts(body)).containsExactly("porter.in/rd/2ece6fcccd")
        assertThat(urls(body)).containsExactly("https://porter.in/rd/2ece6fcccd")
    }

    @Test
    fun `an https link keeps its own scheme`() {
        val body = "Click to deactivate https://viapp.onelink.me/bSC3/hns1 and select 'Others'"

        assertThat(urls(body)).containsExactly("https://viapp.onelink.me/bSC3/hns1")
    }

    @Test
    fun `a sentence-ending full stop stays out of the link`() {
        val body = "View/Download Statement on bobcard.io/App. Know more: bobcard.io/Pymt."

        assertThat(texts(body)).containsExactly("bobcard.io/App", "bobcard.io/Pymt")
    }

    @Test
    fun `amounts account tails and dates are not links`() {
        val body =
            "Statement for BOBCARD **1234 for AUG26 is generated. Pay Total: Rs 4210.5 or " +
                "Min Due: Rs 310 by 13-09-26. A/c XX4321 debited Rs.2,878.80 on 28-08-26."

        assertThat(BodyLinkFinder.find(body)).isEmpty()
    }

    @Test
    fun `a bare mobile number becomes a dialable link`() {
        val body = "Your delivery agent Shafiulla can be reached on 9871112222 before 6pm"

        val link = BodyLinkFinder.find(body).single()
        assertThat(link.text).isEqualTo("9871112222")
        assertThat(link.url).isEqualTo("tel:9871112222")
        assertThat(link.kind).isEqualTo(BodyLinkKind.PHONE)
    }

    @Test
    fun `an international number keeps its country code`() {
        val body = "Call +91 98765 43210 for assistance"

        val link = BodyLinkFinder.find(body).single()
        assertThat(link.kind).isEqualTo(BodyLinkKind.PHONE)
        assertThat(link.url).isEqualTo("tel:+919876543210")
    }

    @Test
    fun `a ten digit PNR is NOT dialable`() {
        // The sharpest trap in this corpus: an Indian PNR is ten digits, the
        // same shape as a mobile number.
        val body = "PNR-1234567890\nTrn:22345\nDt:24-08-26\nFrm BXR to AY"

        assertThat(BodyLinkFinder.find(body)).isEmpty()
    }

    @Test
    fun `reference and transaction ids are NOT dialable`() {
        for (body in listOf(
            "Transaction No. 2998038740 for Rs. 56.00 done",
            "Your order 9876543210 has shipped",
            "Ref no 9123456780 for your claim",
            "A/c 9988776655 credited",
            "Card 9876543210 blocked",
            "Ticket 9876501234 raised",
        )) {
            assertThat(BodyLinkFinder.find(body).filter { it.kind == BodyLinkKind.PHONE }).isEmpty()
        }
    }

    @Test
    fun `a helpline short code is not linked`() {
        // Three and four digit runs are everywhere here (amounts, years), so a
        // dialer opening on "Rs 200" is the worse failure.
        val body = "For Enquiry/Complaint/Assistance,please dial 139 IR-CRIS"

        assertThat(BodyLinkFinder.find(body)).isEmpty()
    }

    @Test
    fun `long digit runs like card and account numbers are not dialable`() {
        for (body in listOf(
            "4111111111111111 charged",
            "UTR 987654321012345 settled",
            "12345678901 is your consumer number",
        )) {
            assertThat(BodyLinkFinder.find(body).filter { it.kind == BodyLinkKind.PHONE }).isEmpty()
        }
    }

    @Test
    fun `an amount is never dialable`() {
        val body = "Rs 9876543210 debited"

        assertThat(BodyLinkFinder.find(body).filter { it.kind == BodyLinkKind.PHONE }).isEmpty()
    }

    @Test
    fun `a tel link written out by the sender is honoured`() {
        val body = "Reach support at tel:18001234567 any time"

        val link = BodyLinkFinder.find(body).single()
        assertThat(link.kind).isEqualTo(BodyLinkKind.PHONE)
        assertThat(link.url).startsWith("tel:")
    }

    @Test
    fun `a number inside a url path does not become a second link`() {
        val body = "Track at example.com/orders/9876543210 now"

        val links = BodyLinkFinder.find(body)
        assertThat(links).hasSize(1)
        assertThat(links.single().kind).isEqualTo(BodyLinkKind.WEB)
    }

    @Test
    fun `an email address becomes a mailto link`() {
        val body = "Write to care@example.com for help"

        assertThat(urls(body)).containsExactly("mailto:care@example.com")
        assertThat(BodyLinkFinder.find(body).single().kind).isEqualTo(BodyLinkKind.EMAIL)
    }

    @Test
    fun `a upi payment link is tappable and typed as a payment`() {
        val body = "Approve the request in your UPI app: upi://pay?pa=someone@examplebank&am=50"

        val link = BodyLinkFinder.find(body).single()
        assertThat(link.url).startsWith("upi://pay")
        assertThat(link.kind).isEqualTo(BodyLinkKind.PAYMENT)
    }

    @Test
    fun `several links in one body are all found in order`() {
        val body = "Pay via bobcard.io/App or InstaPay. Know more: bobcard.io/Pymt"

        assertThat(texts(body)).containsExactly("bobcard.io/App", "bobcard.io/Pymt").inOrder()
    }

    @Test
    fun `links never overlap`() {
        val body = "Contact care@example.com or visit example.com/care for support"

        val links = BodyLinkFinder.find(body)
        for (a in links.indices) {
            for (b in a + 1 until links.size) {
                val first = links[a]
                val second = links[b]
                assertThat(first.start < second.end && second.start < first.end).isFalse()
            }
        }
    }

    @Test
    fun `the offsets point at the link text inside the body`() {
        val body = "tap porter.in/rd/abc now"

        val link = BodyLinkFinder.find(body).single()
        assertThat(body.substring(link.start, link.end)).isEqualTo(link.text)
    }

    @Test
    fun `a body with no links yields none`() {
        assertThat(BodyLinkFinder.find("Your OTP is 445566. Do not share it with anyone.")).isEmpty()
    }
}
