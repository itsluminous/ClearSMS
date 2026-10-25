package app.clearsms.ui.components

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * Per-message text direction (GitHub #139): a Persian message in an English
 * UI reads right-to-left and right-aligned, an English one left-to-right and
 * left-aligned - decided from EACH message's own content, never from the
 * phone's language, and never moving the bubble. Outgoing bubbles stay on the
 * right and incoming on the left; only the text inside, and its alignment,
 * follow the text.
 *
 * The decision is the platform's: [TextDirection.Content] hands the paragraph
 * to the Unicode bidirectional algorithm, whose first strong directional
 * character (UAX #9, P2/P3) sets the paragraph direction - the same
 * `firstStrong` heuristic Android's own `TextView` uses by default, which is
 * why Google Messages and Samsung Messages already behave this way. Without
 * it Compose resolves an unspecified direction to the LAYOUT direction, so
 * every message in an English UI is laid out as an LTR paragraph whatever it
 * says. It is a direction per PARAGRAPH, not per character: an English word
 * inside a Persian sentence keeps its own run order inside a right-to-left
 * line, which is exactly what the algorithm is for and what a per-character
 * or per-script switch would break. Text with no strong character (digits,
 * emoji, punctuation, an empty draft) falls back to the layout direction, so
 * nothing changes for it.
 *
 * [TextAlign.Start] is resolved against the PARAGRAPH direction, not the
 * layout direction, so the lines of a Persian message sit against the right
 * edge of their box and English against the left - the "alignment flips with
 * the text" half of the request.
 *
 * Every surface showing message text merges [style] - the bubble body, the
 * text-selection twin of it, the composer field, the inbox, search, archive
 * and bin rows - so they cannot drift (a source contract checks it). The
 * composer is the surface the report is really about: its field lays its
 * text out afresh on every edit, so the paragraph flips to RTL on the first
 * Persian letter typed, and the caret and selection handles follow the
 * paragraph as they do in any Android text field.
 */
object MessageTextDirection {
    /** Merge into a message-text [TextStyle]; see the class doc for what it does. */
    val style: TextStyle = TextStyle(textDirection = TextDirection.Content, textAlign = TextAlign.Start)

    /**
     * The direction [style] resolves [text] to in a UI laid out in
     * [layoutDirection]: a pure statement of the platform rule, for tests to
     * pin down what the rendering above will do. First strong character wins
     * (L, or an LTR embedding/override, is left-to-right; R, AL, or an RTL
     * embedding/override, right-to-left), characters inside an isolate
     * (LRI/RLI/FSI ... PDI) are skipped as the platform skips them, and text
     * with no strong character - digits of any script are weak, emoji and
     * punctuation neutral - keeps the layout direction.
     */
    fun resolve(
        text: CharSequence,
        layoutDirection: LayoutDirection,
    ): LayoutDirection {
        var isolateDepth = 0
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            index += Character.charCount(codePoint)
            when (Character.getDirectionality(codePoint)) {
                Character.DIRECTIONALITY_LEFT_TO_RIGHT_ISOLATE,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ISOLATE,
                Character.DIRECTIONALITY_FIRST_STRONG_ISOLATE,
                -> isolateDepth++

                Character.DIRECTIONALITY_POP_DIRECTIONAL_ISOLATE -> if (isolateDepth > 0) isolateDepth--

                Character.DIRECTIONALITY_LEFT_TO_RIGHT,
                Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING,
                Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE,
                -> if (isolateDepth == 0) return LayoutDirection.Ltr

                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE,
                -> if (isolateDepth == 0) return LayoutDirection.Rtl

                else -> Unit
            }
        }
        return layoutDirection
    }
}
