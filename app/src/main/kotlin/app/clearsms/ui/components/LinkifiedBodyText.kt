package app.clearsms.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

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
    val annotated =
        remember(body, links) {
            buildAnnotatedString {
                var cursor = 0
                for (link in links) {
                    if (link.start > cursor) append(body.substring(cursor, link.start))
                    pushLink(
                        LinkAnnotation.Clickable(
                            tag = link.url,
                            styles =
                                TextLinkStyles(
                                    style =
                                        SpanStyle(
                                            textDecoration = TextDecoration.Underline,
                                            fontWeight = FontWeight.Medium,
                                        ),
                                ),
                        ) { onLinkClick(link) },
                    )
                    append(link.text)
                    pop()
                    cursor = link.end
                }
                if (cursor < body.length) append(body.substring(cursor))
            }
        }
    Text(text = annotated, style = style, color = color, modifier = modifier)
}
