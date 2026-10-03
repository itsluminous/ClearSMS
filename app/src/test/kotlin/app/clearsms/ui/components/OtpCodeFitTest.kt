package app.clearsms.ui.components

import androidx.compose.ui.unit.sp
import app.clearsms.domain.model.OtpDisplaySize
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Pins how the inbox banner's code fits the width the Copy and Dismiss
 * buttons leave it (see [OtpCodeFit]): one line, every character, the
 * user's size as a ceiling, spacing dropped before the floor is crossed,
 * and the floor crossed before anything is ever hidden. No Compose UI
 * harness here (repo convention): the fit decision is a pure function over
 * a measurer, exercised with the monospace model below, plus a source
 * contract on the banner's wiring.
 *
 * The model: Android's monospace (Droid Sans Mono) advances 0.6em per
 * glyph, so a string is `length x 0.6 x sp x fontScale` dp wide - the same
 * arithmetic the contract doc uses for its 115dp / 172dp proof.
 */
class OtpCodeFitTest {
    private fun source(path: String) = File("src/main/kotlin/app/clearsms/$path").readText()

    private fun mono(scale: Float): (String, Int) -> Float = { text, sp -> text.length * 0.6f * sp * scale }

    /** Width left for the code on a [screenDp]-wide phone: list padding, card padding, two buttons. */
    private fun slot(screenDp: Int): Float = (screenDp - 2 * 16 - (20 + 8) - (40 + 48)).toFloat()

    private val ceilings = OtpDisplaySize.entries.map { otpBannerFontSp(it) }
    private val codes = listOf("4829", "48291", "482910", "4829105", "48291057", "4829105731", "A1B2C3D4")
    private val scales = listOf(1.0f, 1.3f, 1.5f, 2.0f)
    private val screens = listOf(320, 360, 411, 480, 640, 800)

    @Test
    fun `the screenshot's case - six digits at the largest setting - is one line at every scale and width`() {
        for (scale in scales) {
            for (screen in screens) {
                val plan = OtpCodeFit.plan("229682", otpBannerFontSp(OtpDisplaySize.OPTION_5), slot(screen), mono(scale))
                assertThat(plan.fitsOnOneLine).isTrue()
                assertThat(plan.code).isEqualTo("229682")
                assertThat(mono(scale)(plan.text, plan.fontSp)).isAtMost(slot(screen))
                // ...and at the readability floor or above: the last resort is never needed for it.
                assertThat(plan.fontSp).isAtLeast(OtpCodeFit.MinFontSize.value.toInt())
            }
        }
    }

    @Test
    fun `a code that fits at the ceiling renders exactly as before - spaced, at the setting's size`() {
        // 480dp phone, scale 1.0, 6 digits, largest setting: 11 x 0.6 x 50 = 330dp of 332.
        val plan = OtpCodeFit.plan("482910", 50, slot(480), mono(1.0f))
        assertThat(plan).isEqualTo(OtpCodeFit.Plan("4 8 2 9 1 0", true, 20, 50, 50, true))
        // The default setting's 6 digits fit every phone from 360dp at scale 1.0 (211.2 of 212)...
        for (screen in screens.filter { it >= 360 }) {
            assertThat(OtpCodeFit.plan("482910", 32, slot(screen), mono(1.0f)).let { it.spaced to it.fontSp }).isEqualTo(true to 32)
        }
        // ...and wherever the spaced code fits at the ceiling, the plan IS the ceiling, spaced: no visual change.
        var untouched = 0
        for (code in codes) for (ceiling in ceilings) for (scale in scales) for (screen in screens) {
            val width = slot(screen)
            if (mono(scale)(OtpCodeFit.spaced(code), ceiling) <= width) {
                val p = OtpCodeFit.plan(code, ceiling, width, mono(scale))
                assertThat(p).isEqualTo(OtpCodeFit.Plan(OtpCodeFit.spaced(code), true, 20, ceiling, ceiling, true))
                untouched++
            }
        }
        assertThat(untouched).isGreaterThan(100)
    }

    @Test
    fun `the chosen size never exceeds the user's ceiling, and the range handed to the auto-sizer is sane`() {
        for (code in codes) for (ceiling in ceilings) for (scale in scales) for (screen in screens) {
            val plan = OtpCodeFit.plan(code, ceiling, slot(screen), mono(scale))
            assertThat(plan.fontSp).isAtMost(ceiling)
            assertThat(plan.maxFontSp).isEqualTo(ceiling)
            assertThat(plan.minFontSp).isAtMost(plan.fontSp)
            assertThat(plan.fontSp).isAtLeast(OtpCodeFit.LastResortMinFontSize.value.toInt())
        }
        // A ceiling under the floor cannot produce an inverted range.
        assertThat(OtpCodeFit.plan("482910", 10, 1000f, mono(1.0f)).maxFontSp).isEqualTo(20)
    }

    @Test
    fun `the result never drops a character - not even when nothing fits`() {
        val widths = listOf(0f, 1f, 50f, 100f, slot(320), slot(360), 10_000f)
        for (code in codes) for (ceiling in ceilings) for (scale in scales) for (width in widths) {
            val plan = OtpCodeFit.plan(code, ceiling, width, mono(scale))
            assertThat(plan.code).isEqualTo(code)
            assertThat(plan.text.replace(" ", "")).isEqualTo(code)
            assertThat(plan.text.length).isAnyOf(code.length, 2 * code.length - 1)
        }
        // The most extreme case: a zero-width slot. The plan still carries
        // the whole code, at the last-resort size, flagged as not fitting -
        // which the banner draws in full past its slot (Visible overflow).
        val none = OtpCodeFit.plan("48291057", 50, 0f, mono(2.0f))
        assertThat(none.code).isEqualTo("48291057")
        assertThat(none.fitsOnOneLine).isFalse()
        assertThat(none.fontSp).isEqualTo(12)
        assertThat(none.minFontSp).isEqualTo(12)
    }

    @Test
    fun `degradation order - shrink spaced to the floor, then unspace and re-maximise, then cross the floor`() {
        val measure = mono(2.0f)
        // Six digits at scale 2.0: spaced costs 11 glyphs, unspaced 6.
        // Wide enough for spaced at 50: 11 x 0.6 x 50 x 2 = 660.
        assertThat(OtpCodeFit.plan("482910", 50, 661f, measure)).isEqualTo(OtpCodeFit.Plan("4 8 2 9 1 0", true, 20, 50, 50, true))
        // A little narrower: still spaced, shrunk only as far as needed (13.2/sp -> 40sp needs 528).
        assertThat(OtpCodeFit.plan("482910", 50, 530f, measure).let { it.spaced to it.fontSp }).isEqualTo(true to 40)
        // Spaced at the floor is 264; just above that it is spaced at exactly the floor...
        assertThat(OtpCodeFit.plan("482910", 50, 264.5f, measure).let { it.spaced to it.fontSp }).isEqualTo(true to 20)
        // ...one unit narrower, spacing goes and the size is RE-MAXIMISED under
        // the ceiling: unspaced costs 7.2/sp, so 263 -> 36sp, larger than the
        // floor the spaced form had reached. The floor stays the auto-sizer's minimum.
        assertThat(OtpCodeFit.plan("482910", 50, 263f, measure)).isEqualTo(OtpCodeFit.Plan("482910", false, 20, 50, 36, true))
        // Unspaced at the floor is 144: narrower than that crosses the floor
        // (step 3) - and ONLY then does the auto-sizer's minimum drop.
        assertThat(OtpCodeFit.plan("482910", 50, 144.5f, measure)).isEqualTo(OtpCodeFit.Plan("482910", false, 20, 50, 20, true))
        assertThat(OtpCodeFit.plan("482910", 50, 143.5f, measure)).isEqualTo(OtpCodeFit.Plan("482910", false, 12, 50, 19, true))
        // The last resort, 12sp, is 86.4 wide; below that the plan admits it does not fit.
        assertThat(OtpCodeFit.plan("482910", 50, 87f, measure).fitsOnOneLine).isTrue()
        assertThat(OtpCodeFit.plan("482910", 50, 86f, measure).fitsOnOneLine).isFalse()
    }

    @Test
    fun `on any phone, every 4-8 digit code fits on one line - the last resort is unreachable`() {
        // The extractor yields \d{4,8}; the narrowest Android phone is 320dp;
        // the largest font scale 2.0. Nothing in that envelope fails to fit.
        for (code in codes.filter { it.length in 4..8 && it.all(Char::isDigit) }) {
            for (ceiling in ceilings) for (scale in scales) for (screen in screens) {
                val plan = OtpCodeFit.plan(code, ceiling, slot(screen), mono(scale))
                assertThat(plan.fitsOnOneLine).isTrue()
                assertThat(plan.fontSp).isAtLeast(12)
                // Up to 7 digits, or any phone from 360dp, never even crosses the floor.
                if (code.length <= 7 || screen >= 360) {
                    assertThat(plan.minFontSp).isEqualTo(20)
                    assertThat(plan.fontSp).isAtLeast(20)
                }
            }
        }
        // The contract doc's figures: 8 unspaced digits at 12sp x 2.0 are
        // 115dp, the 320dp slot is 172dp.
        assertThat(mono(2.0f)("48291057", 12)).isWithin(0.5f).of(115.2f)
        assertThat(slot(320)).isEqualTo(172f)
        // The one place the floor is crossed: 8 digits on a 320dp phone at
        // scale 2.0 - spaced needs 15 x 0.6 x 20 x 2 = 360, unspaced at the
        // floor 192, both over 172 - lands unspaced at 17sp (34sp physical),
        // whole, on one line.
        val tight = OtpCodeFit.plan("48291057", 50, slot(320), mono(2.0f))
        assertThat(tight).isEqualTo(OtpCodeFit.Plan("48291057", false, 12, 50, 17, true))
        // At scale 1.0 the same code on the same phone keeps the floor (unspaced, 35sp).
        assertThat(OtpCodeFit.plan("48291057", 50, slot(320), mono(1.0f))).isEqualTo(OtpCodeFit.Plan("48291057", false, 20, 50, 35, true))
    }

    @Test
    fun `the floors are sp, pinned, and the step is whole sp`() {
        assertThat(OtpCodeFit.MaxLines).isEqualTo(1)
        assertThat(OtpCodeFit.MinFontSize).isEqualTo(20.sp)
        assertThat(OtpCodeFit.MinFontSize.isSp).isTrue()
        // The settings dialog previews the DEFAULT option's digits at exactly this size.
        assertThat(otpPreviewFontSp(OtpDisplaySize.DEFAULT)).isEqualTo(20)
        // Below the smallest banner option, so Option 1 can shrink too.
        assertThat(OtpCodeFit.MinFontSize.value).isLessThan(otpBannerFontSp(OtpDisplaySize.OPTION_1).toFloat())
        assertThat(OtpCodeFit.LastResortMinFontSize).isEqualTo(12.sp)
        assertThat(OtpCodeFit.LastResortMinFontSize.isSp).isTrue()
        assertThat(OtpCodeFit.StepSize).isEqualTo(1.sp)
        assertThat(OtpCodeFit.spaced("482910")).isEqualTo("4 8 2 9 1 0")
    }

    @Test
    fun `the banner draws the code through the auto-sizing path - one line, no wrap, no clip, no ellipsis`() {
        val banner = source("ui/components/OtpBanner.kt")
        val codeText = banner.substring(banner.indexOf("private fun OtpCode("), banner.indexOf("@Preview"))
        // BasicText + TextAutoSize.StepBased, like the title; Material3 Text has no autoSize.
        assertThat(codeText).contains("BasicText(")
        assertThat(codeText).contains("TextAutoSize.StepBased(")
        assertThat(codeText).contains("maxLines = OtpCodeFit.MaxLines,")
        assertThat(codeText).contains("softWrap = false,")
        assertThat(codeText).contains("overflow = TextOverflow.Visible,")
        assertThat(codeText).doesNotContain("TextOverflow.Ellipsis")
        assertThat(codeText).doesNotContain("TextOverflow.Clip")
        // The range comes from the plan, not literals of the call site's own.
        assertThat(codeText).contains("minFontSize = plan.minFontSp.sp,")
        assertThat(codeText).contains("maxFontSize = plan.maxFontSp.sp,")
        assertThat(codeText).contains("stepSize = OtpCodeFit.StepSize,")
        assertThat(codeText).doesNotContainMatch("minFontSize = \\d")
        // The drawn text is the plan's (spaced or not); the ceiling is the user's banner size.
        assertThat(codeText).contains("text = plan.text,")
        assertThat(banner).contains("OtpCode(code = code, ceilingSp = otpBannerFontSp(displaySize))")
        // The plan is measured against the real slot width with the real text engine.
        assertThat(codeText).contains("BoxWithConstraints(")
        assertThat(codeText).contains("availableWidth = maxWidthPx.toFloat(),")
        assertThat(codeText).contains("rememberTextMeasurer()")
        // No second mechanism: the old fixed-size Text with its own join is gone.
        assertThat(banner).doesNotContain("joinToString(\" \")")
        assertThat(banner).doesNotContain("fontSize = otpBannerFontSp(displaySize).sp")
    }

    @Test
    fun `the announcement is the spaced code whatever is drawn, and the clipboard gets the unspaced code`() {
        val banner = source("ui/components/OtpBanner.kt")
        val codeText = banner.substring(banner.indexOf("private fun OtpCode("), banner.indexOf("@Preview"))
        // Semantics pinned to the spaced form: the screen reader hears digit
        // by digit (as it did before, when the spaced string WAS the text)
        // even when the plan drew the digits without spaces.
        assertThat(codeText).contains("val announced = OtpCodeFit.spaced(code)")
        assertThat(codeText).contains("clearAndSetSemantics {")
        assertThat(codeText).contains("text = AnnotatedString(announced)")
        assertThat(codeText).doesNotContain("contentDescription")
        // Copy puts the raw code on the clipboard - never the drawn text.
        assertThat(banner).contains("clipboard.setText(AnnotatedString(code))")
        assertThat(banner).doesNotContain("setText(AnnotatedString(plan.")
    }
}
