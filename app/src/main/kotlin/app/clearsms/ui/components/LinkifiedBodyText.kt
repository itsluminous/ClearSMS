package app.clearsms.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.DpSize

/**
 * A message body whose web and email links are tappable.
 *
 * Links are underlined and slightly bolder rather than recoloured: bubbles
 * come in several background colours, and an accent that stays legible on all
 * of them does not exist - the surrounding text colour always does.
 *
 * Taps are routed through [onLinkClick] with the whole [BodyLink] - not just
 * its URL - instead of Compose's default URL handling, so the caller can act
 * on the KIND: a scam-flagged message warns differently about a payment, a
 * phone call and a web page, and a device with no app for the scheme can say
 * so in a snackbar instead of throwing.
 *
 * A link is tappable on its own glyphs and nowhere else. Compose lays a
 * clickable box over each link's bounding rectangle, clipped to the glyph
 * outline - but a box shorter than the platform's minimum touch target (one
 * text line always is) gets that target expanded, and the expansion ignores
 * the clip: a tap up to ~12dp above or below the link, or on the plain words
 * sharing a line with a wrapped link, is a "near hit" on the link that beats
 * the bubble's own tap and long-press. In a bubble that means tapping "See"
 * opened the URL three words later instead of expanding the details. The
 * [ViewConfiguration] below switches that expansion off for this text only;
 * everything else about input (touch slop, long-press timeout) is inherited.
 */
@Composable
fun LinkifiedBodyText(
    body: String,
    onLinkClick: (BodyLink) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    val links = remember(body) { BodyLinkFinder.find(body) }
    if (links.isEmpty()) {
        Text(text = body, style = style, color = color, modifier = modifier)
        return
    }
    val annotated = remember(body, links) { LinkifiedBody.annotate(body, links, onLinkClick) }
    val inherited = LocalViewConfiguration.current
    val exactHits = remember(inherited) { ExactLinkHitTesting(inherited) }
    CompositionLocalProvider(LocalViewConfiguration provides exactHits) {
        Text(text = annotated, style = style, color = color, modifier = modifier)
    }
}

/**
 * The inherited input configuration with the minimum touch target removed, so
 * a link's clickable area is exactly its glyphs. See [LinkifiedBodyText].
 */
internal class ExactLinkHitTesting(
    private val inherited: ViewConfiguration,
) : ViewConfiguration by inherited {
    override val minimumTouchTargetSize: DpSize get() = DpSize.Zero
}

/** Builds the annotated body that [LinkifiedBodyText] renders. */
internal object LinkifiedBody {
    private val linkStyles =
        TextLinkStyles(
            style =
                SpanStyle(
                    textDecoration = TextDecoration.Underline,
                    fontWeight = FontWeight.Medium,
                ),
        )

    /**
     * The one invariant: the result's text IS [body], character for
     * character. Every link annotation is placed by offset into that text, so
     * a single duplicated or dropped run would shift every later link onto the
     * wrong characters and a tap on plain text would open a link. The body is
     * therefore appended from the body itself - never from [BodyLink.text] -
     * and the finder's output is sanitised rather than trusted: ranges outside
     * the body are ignored, ranges are visited in ascending order, and where
     * two overlap the earlier (and, on a tie, longer) one wins, the same rule
     * [BodyLinkFinder] applies. The finder already emits clean ranges; this
     * guards against the finder of the future.
     */
    fun annotate(
        body: String,
        links: List<BodyLink>,
        onLinkClick: (BodyLink) -> Unit,
    ): AnnotatedString =
        buildAnnotatedString {
            var cursor = 0
            for (link in placeable(body, links)) {
                append(body, cursor, link.start)
                pushLink(LinkAnnotation.Clickable(tag = link.url, styles = linkStyles) { onLinkClick(link) })
                append(body, link.start, link.end)
                pop()
                cursor = link.end
            }
            append(body, cursor, body.length)
        }

    /** The links that can be annotated onto [body]: in bounds, ascending, disjoint. */
    fun placeable(
        body: String,
        links: List<BodyLink>,
    ): List<BodyLink> {
        val ordered =
            links
                .filter { it.start >= 0 && it.start < it.end && it.end <= body.length }
                .sortedWith(compareBy({ it.start }, { -(it.end - it.start) }))
        val kept = ArrayList<BodyLink>(ordered.size)
        var cursor = 0
        for (link in ordered) {
            if (link.start < cursor) continue
            kept += link
            cursor = link.end
        }
        return kept
    }
}
