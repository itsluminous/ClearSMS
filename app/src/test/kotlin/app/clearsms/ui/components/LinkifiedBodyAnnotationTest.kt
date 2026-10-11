package app.clearsms.ui.components

import androidx.compose.ui.text.LinkAnnotation
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The one invariant that keeps a tap on plain text from opening a link: the
 * annotated string [LinkifiedBodyText] renders must be the body, character
 * for character, with every link annotation on exactly its own characters.
 * Once that drifts - a duplicated or dropped run - every later link sits on
 * the wrong characters and "See" opens the URL that comes three words later.
 *
 * The invariant is pinned twice: over what [BodyLinkFinder] emits today, and
 * over deliberately broken finder output, so a future finder change cannot
 * shift annotations onto plain text.
 */
@RunWith(RobolectricTestRunner::class)
class LinkifiedBodyAnnotationTest {
    private val corpus =
        listOf(
            "See https://example.com/offers for details",
            "Chat on https://wa.me/919876543210 or call 9876543210 today",
            "Write to https://example.com/contact/care@example.com or care@example.com",
            "Pay via upi://pay?pa=shop@upi&pn=Shop&url=https://shop.in/x&am=10 before 6pm",
            "Contact care@example.com or visit example.com/care for support",
            "Call +91 98765 43210 or tel:18001234567, see porter.in/rd/2ece6fcccd.",
            "View/Download Statement on bobcard.io/App. Know more: bobcard.io/Pymt.",
            "Your OTP is 445566. Do not share it with anyone.",
        )

    private fun web(
        body: String,
        text: String,
        from: Int = body.indexOf(text),
    ) = BodyLink(start = from, end = from + text.length, text = text, url = "https://$text")

    private fun assertAnnotatedOnItsOwnCharacters(
        body: String,
        links: List<BodyLink>,
    ) {
        val annotated = LinkifiedBody.annotate(body, links) {}
        assertThat(annotated.text).isEqualTo(body)
        var previousEnd = 0
        for (range in annotated.getLinkAnnotations(0, body.length)) {
            assertThat(range.start).isAtLeast(previousEnd)
            assertThat(range.end).isAtMost(body.length)
            val tag = (range.item as LinkAnnotation.Clickable).tag
            val link = links.single { it.url == tag && it.start == range.start && it.end == range.end }
            assertThat(body.substring(range.start, range.end)).isEqualTo(link.text)
            previousEnd = range.end
        }
    }

    @Test
    fun `the annotated text is the body and each link sits on its own characters`() {
        for (body in corpus) assertAnnotatedOnItsOwnCharacters(body, BodyLinkFinder.find(body))
    }

    @Test
    fun `every link the finder emits today is annotated`() {
        for (body in corpus) {
            val links = BodyLinkFinder.find(body)
            val annotated = LinkifiedBody.annotate(body, links) {}
            assertThat(annotated.getLinkAnnotations(0, body.length).map { (it.item as LinkAnnotation.Clickable).tag })
                .containsExactlyElementsIn(links.map { it.url })
                .inOrder()
        }
    }

    @Test
    fun `a tap offset inside plain text maps to no link, inside a link to that link`() {
        val body = "See https://example.com/offers for details"
        val annotated = LinkifiedBody.annotate(body, BodyLinkFinder.find(body)) {}

        for (offset in 0 until 4) assertThat(annotated.getLinkAnnotations(offset, offset + 1)).isEmpty()
        for (offset in body.indexOf(" for") until body.length) {
            assertThat(annotated.getLinkAnnotations(offset, offset + 1)).isEmpty()
        }
        for (offset in body.indexOf("https") until body.indexOf(" for")) {
            val hit = annotated.getLinkAnnotations(offset, offset + 1).single()
            assertThat((hit.item as LinkAnnotation.Clickable).tag).isEqualTo("https://example.com/offers")
        }
    }

    @Test
    fun `overlapping finder output keeps the earlier, longer link and never duplicates text`() {
        val body = "See example.com/orders/9876543210 now"
        val url = web(body, "example.com/orders/9876543210")
        val phone = BodyLink(url.start + 19, url.end, "9876543210", "tel:9876543210", BodyLinkKind.PHONE)
        val links = listOf(phone, url)

        assertAnnotatedOnItsOwnCharacters(body, links)
        assertThat(LinkifiedBody.placeable(body, links)).containsExactly(url)
    }

    @Test
    fun `out of order and out of bounds finder output is sorted and clipped`() {
        val body = "a.com then b.com"
        val a = web(body, "a.com")
        val b = web(body, "b.com")
        val beyond = BodyLink(start = 12, end = body.length + 3, text = "b.com???", url = "https://x")
        val negative = BodyLink(start = -1, end = 4, text = "?a.co", url = "https://y")
        val empty = BodyLink(start = 6, end = 6, text = "", url = "https://z")
        val links = listOf(b, beyond, negative, empty, a)

        assertAnnotatedOnItsOwnCharacters(body, links)
        assertThat(LinkifiedBody.placeable(body, links)).containsExactly(a, b).inOrder()
    }

    @Test
    fun `a link whose text disagrees with the body is annotated on the body's characters`() {
        val body = "see a.com"
        val wrongText = BodyLink(start = 4, end = 9, text = "A.COM!", url = "https://a.com")

        val annotated = LinkifiedBody.annotate(body, listOf(wrongText)) {}
        assertThat(annotated.text).isEqualTo(body)
        assertThat(annotated.getLinkAnnotations(0, body.length).single().start).isEqualTo(4)
    }

    @Test
    fun `the rendered text does not expand a link's touch target beyond its glyphs`() {
        // Compose expands any clickable shorter than the platform minimum touch
        // target (a text line always is) and the expansion ignores the glyph
        // clip, so a tap just above a link - or on plain words sharing a line
        // with a wrapped link - became a hit on the link. The renderer provides
        // a ViewConfiguration without that minimum around its Text.
        val source =
            File("src/main/kotlin/app/clearsms/ui/components/LinkifiedBodyText.kt").readText()

        assertThat(source).contains("LocalViewConfiguration provides exactHits")
        assertThat(source).contains("override val minimumTouchTargetSize: DpSize get() = DpSize.Zero")
        assertThat(ExactLinkHitTesting(FakeViewConfiguration).minimumTouchTargetSize).isEqualTo(
            androidx.compose.ui.unit.DpSize.Zero,
        )
        assertThat(ExactLinkHitTesting(FakeViewConfiguration).longPressTimeoutMillis).isEqualTo(123L)
    }

    private object FakeViewConfiguration : androidx.compose.ui.platform.ViewConfiguration {
        override val longPressTimeoutMillis = 123L
        override val doubleTapTimeoutMillis = 1L
        override val doubleTapMinTimeMillis = 1L
        override val touchSlop = 1f
    }
}
