package app.clearsms.ui.components

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The per-message direction rule (GitHub #139), stated as a pure function and
 * pinned down on synthesised samples: Persian reads RTL, English LTR, and the
 * FIRST strong character decides a mixed message - an English word inside a
 * Persian sentence does not make it LTR, nor the reverse. Text with no strong
 * character keeps the UI's direction, so an English UI's digits-only, emoji
 * and empty drafts are untouched.
 *
 * The Persian below is this test's own; none of it comes from any report.
 */
class MessageTextDirectionTest {
    // "Hello, I'll see you tomorrow" / "I read the new book" - written here.
    private val persianGreeting = "سلام، فردا می‌بینمت"
    private val persianSentence = "کتاب جدید را خواندم"

    private fun resolveLtrUi(text: String) = MessageTextDirection.resolve(text, LayoutDirection.Ltr)

    @Test
    fun `persian-only text reads right to left`() {
        assertThat(resolveLtrUi(persianGreeting)).isEqualTo(LayoutDirection.Rtl)
        assertThat(resolveLtrUi(persianSentence)).isEqualTo(LayoutDirection.Rtl)
    }

    @Test
    fun `english-only text reads left to right, even in an RTL UI`() {
        assertThat(resolveLtrUi("See you tomorrow at five")).isEqualTo(LayoutDirection.Ltr)
        assertThat(MessageTextDirection.resolve("See you tomorrow", LayoutDirection.Rtl)).isEqualTo(LayoutDirection.Ltr)
    }

    @Test
    fun `persian with an embedded english word stays right to left`() {
        // "I sent the PDF file" - the Latin acronym is inside an RTL paragraph.
        assertThat(resolveLtrUi("فایل PDF را فرستادم")).isEqualTo(LayoutDirection.Rtl)
    }

    @Test
    fun `english with an embedded persian word stays left to right`() {
        assertThat(resolveLtrUi("Call me at ساعت پنج please")).isEqualTo(LayoutDirection.Ltr)
    }

    @Test
    fun `leading neutrals do not decide, the first strong character does`() {
        // Punctuation, spaces and digits before the first letter are skipped.
        assertThat(resolveLtrUi("  (۱) $persianSentence")).isEqualTo(LayoutDirection.Rtl)
        assertThat(resolveLtrUi("...12 tickets booked")).isEqualTo(LayoutDirection.Ltr)
    }

    @Test
    fun `digits of any script are weak and keep the UI direction`() {
        for (text in listOf("12345", "۱۲۳۴۵", "٠١٢٣", "+98 912 000 0000", "3.14")) {
            assertWithMessage(text).that(resolveLtrUi(text)).isEqualTo(LayoutDirection.Ltr)
            assertWithMessage(text).that(MessageTextDirection.resolve(text, LayoutDirection.Rtl)).isEqualTo(LayoutDirection.Rtl)
        }
    }

    @Test
    fun `emoji-only and punctuation-only text keep the UI direction`() {
        for (text in listOf("😀🎉", "👍", "?!", "...")) {
            assertWithMessage(text).that(resolveLtrUi(text)).isEqualTo(LayoutDirection.Ltr)
            assertWithMessage(text).that(MessageTextDirection.resolve(text, LayoutDirection.Rtl)).isEqualTo(LayoutDirection.Rtl)
        }
    }

    @Test
    fun `empty and blank text keep the UI direction`() {
        assertThat(resolveLtrUi("")).isEqualTo(LayoutDirection.Ltr)
        assertThat(resolveLtrUi("   ")).isEqualTo(LayoutDirection.Ltr)
        assertThat(MessageTextDirection.resolve("", LayoutDirection.Rtl)).isEqualTo(LayoutDirection.Rtl)
    }

    @Test
    fun `an isolated run is skipped, as the platform skips it`() {
        // A phone number wrapped in a first-strong isolate (what BidiFormatter
        // emits) must not decide the paragraph; the Persian after it does.
        val isolated = "\u2068" + "+98 912" + "\u2069" + " " + persianGreeting
        assertThat(resolveLtrUi(isolated)).isEqualTo(LayoutDirection.Rtl)
        // An isolate containing Latin letters is skipped too.
        val isolatedLatin = "\u2066" + "PDF" + "\u2069" + " " + persianSentence
        assertThat(resolveLtrUi(isolatedLatin)).isEqualTo(LayoutDirection.Rtl)
    }

    @Test
    fun `the rendering style asks the platform bidi algorithm and aligns to the paragraph start`() {
        // Content: the platform's first-strong heuristic (TextView's default),
        // falling back to the layout direction - the rule resolve() states.
        assertThat(MessageTextDirection.style.textDirection).isEqualTo(TextDirection.Content)
        // Start resolves against the paragraph direction, so Persian lines
        // sit against the right edge and English against the left.
        assertThat(MessageTextDirection.style.textAlign).isEqualTo(TextAlign.Start)
    }
}
