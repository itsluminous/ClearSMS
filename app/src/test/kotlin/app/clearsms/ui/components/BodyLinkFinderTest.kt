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
    fun `a phone number is deliberately not linked`() {
        // Bodies are full of digit groups; a wrong tel: link is worse than none.
        val body = "For Enquiry/Complaint/Assistance,please dial 139 IR-CRIS"

        assertThat(BodyLinkFinder.find(body)).isEmpty()
    }

    @Test
    fun `an email address becomes a mailto link`() {
        val body = "Write to care@example.com for help"

        assertThat(urls(body)).containsExactly("mailto:care@example.com")
    }

    @Test
    fun `a upi payment link is deliberately NOT tappable`() {
        // Opening a payment app with a stranger's amount pre-filled is the
        // exact flow SMS scams use; payment requests stay notices, not actions.
        val body = "Approve the request in your UPI app: upi://pay?pa=someone@examplebank&am=50"

        assertThat(urls(body).none { it.startsWith("upi://") }).isTrue()
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
