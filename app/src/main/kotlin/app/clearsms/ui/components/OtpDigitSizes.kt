package app.clearsms.ui.components

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.clearsms.domain.model.OtpDisplaySize

/**
 * Font size (sp) of the OTP digits in the settings dialog preview.
 * Strictly increasing from Option 1 to Option 5; Option 2 (the default)
 * keeps the size the old "Default" entry rendered at.
 */
fun otpPreviewFontSp(size: OtpDisplaySize): Int =
    when (size) {
        OtpDisplaySize.OPTION_1 -> 16
        OtpDisplaySize.OPTION_2 -> 20
        OtpDisplaySize.OPTION_3 -> 24
        OtpDisplaySize.OPTION_4 -> 30
        OtpDisplaySize.OPTION_5 -> 36
    }

/**
 * Font size (sp) of the OTP digits in the in-app inbox banner. Larger than
 * the dialog preview (the banner is the primary reading surface) but the
 * same strictly increasing progression.
 */
fun otpBannerFontSp(size: OtpDisplaySize): Int =
    when (size) {
        OtpDisplaySize.OPTION_1 -> 26
        OtpDisplaySize.OPTION_2 -> 32
        OtpDisplaySize.OPTION_3 -> 38
        OtpDisplaySize.OPTION_4 -> 44
        OtpDisplaySize.OPTION_5 -> 50
    }

/**
 * How the inbox banner's code fits the width the Copy and Dismiss buttons
 * leave it: on ONE line, every character drawn, shrinking from the user's
 * size setting (the CEILING - a code that fits renders exactly as before)
 * no further than needed. A title may ellipsise; a code may not - a user
 * reading a truncated code enters the wrong one - so the degradation order
 * when the ceiling does not fit is, in priority:
 *
 *  1. shrink the spaced code ("4 8 2 9 1 0") toward [MinFontSize];
 *  2. drop the inter-digit spacing - "482910" is 6 glyphs where the spaced
 *     form is 11, so this nearly halves the width - and re-maximise the size
 *     under the ceiling, since the setting is a ceiling, not a target;
 *  3. only if even the UNSPACED code is too wide at the floor, shrink past
 *     the floor toward [LastResortMinFontSize];
 *  4. and if even that is too wide, the code is laid out at the last-resort
 *     size and PAINTED PAST its slot ([androidx.compose.ui.text.style.TextOverflow.Visible],
 *     `softWrap = false`, `maxLines = 1`) - over the buttons' margin, never
 *     clipped by the text itself, never ellipsised, never wrapped.
 *
 * Step 4 is what makes the contract provable without arithmetic: a
 * single-line, non-wrapping, Visible-overflow layout contains every glyph
 * of its string whatever the width, so no step can lose a character. Steps
 * 1-3 are what keep step 4 unreachable in practice: the extractor yields
 * 4-8 digits (`OtpParser`, `\d{4,8}`), and 8 unspaced digits at the
 * 12sp last resort are 8 x 0.6em x 12sp x 2.0 (the largest Android font
 * scale) = 115dp - narrower than the 172dp the slot keeps on a 320dp
 * screen (320 - 2x16 list padding - 20 - 8 card padding - 40 - 48
 * buttons), so on any phone the code fits on one line.
 *
 * Both floors are in sp ON PURPOSE (see `ConversationTitleFit`): they scale
 * with the user's font setting, so auto-shrinking can never undo a
 * large-font accessibility choice - at scale 2.0 the smallest a code
 * normally renders is 2 x 20sp.
 *
 * The screen-reader announcement is NOT part of this contract: the banner
 * always announces the spaced form (digit by digit), whatever the plan
 * chose to draw, and the clipboard always receives the unspaced code.
 */
object OtpCodeFit {
    /** A code is one line. Never two. */
    val MaxLines: Int = 1

    /** Between every character of the drawn code, until spacing is dropped. */
    val DigitSeparator: String = " "

    /**
     * Readability floor: the size the settings dialog previews the DEFAULT
     * option's digits at ([otpPreviewFontSp] of `OPTION_2`), so it is a
     * size the app already presents as a readable code, and 25% above the
     * 16sp body text the title's floor sits at - a code needs more than a
     * name does, each glyph must be told apart. Below the smallest banner
     * option (26sp) so Option 1 has room to shrink too.
     */
    val MinFontSize: TextUnit = 20.sp

    /**
     * Step 3's floor: Material3 labelSmall / bodySmall, the smallest text
     * the type scale defines and the smallest this app ever runs. Crossed
     * under only when the unspaced code is too wide at [MinFontSize]: of
     * the extractor's 4-8 digit codes on a 320dp-or-wider screen, that is
     * an 8-digit code on a 320dp screen at font scale above ~1.8 (8 x
     * 0.6em x 20sp x 1.8 = 173dp vs the 172dp slot), which lands at 17sp -
     * 34sp physical at scale 2.0. Never reached itself (see the object doc).
     */
    val LastResortMinFontSize: TextUnit = 12.sp

    /** Auto-size granularity: whole sp steps, like the title. */
    val StepSize: TextUnit = 1.sp

    /** "482910" -> "4 8 2 9 1 0": the drawn AND announced form of a code that has room. */
    fun spaced(code: String): String = code.toCharArray().joinToString(DigitSeparator)

    /**
     * What the banner draws: the [text] (spaced or not), the auto-size
     * range `[minFontSp, maxFontSp]` it hands `TextAutoSize.StepBased`
     * ([maxFontSp] is the user's ceiling, never below the floor), and
     * - for tests and reports - the [fontSp] that range resolves to under
     * [plan]'s measure, and whether it [fitsOnOneLine] at all (false only
     * in step 4 of the object doc).
     */
    data class Plan(
        val text: String,
        val spaced: Boolean,
        val minFontSp: Int,
        val maxFontSp: Int,
        val fontSp: Int,
        val fitsOnOneLine: Boolean,
    ) {
        /** The code with the drawn spacing removed - always the complete code. */
        val code: String get() = text.replace(DigitSeparator, "")
    }

    /**
     * Pure fit decision. [measure] returns the single-line width of [text]
     * at a font size in sp, in the same unit as [availableWidth] (px at
     * runtime, dp in tests); [ceilingSp] is the user's size setting for
     * this surface. Searches the degradation order of the object doc in
     * [StepSize] steps and returns the first state that fits, or the
     * step-4 plan when none does. `result.code == code` for every input.
     */
    fun plan(
        code: String,
        ceilingSp: Int,
        availableWidth: Float,
        measure: (text: String, fontSp: Int) -> Float,
    ): Plan {
        val floor = MinFontSize.value.toInt()
        val lastResort = LastResortMinFontSize.value.toInt()
        val ceiling = maxOf(ceilingSp, floor)
        val stepSp = StepSize.value.toInt()
        val spacedText = spaced(code)
        fun fits(
            text: String,
            fontSp: Int,
        ) = measure(text, fontSp) <= availableWidth

        // 1. spaced, ceiling -> floor
        for (size in ceiling downTo floor step stepSp) {
            if (fits(spacedText, size)) return Plan(spacedText, true, floor, ceiling, size, true)
        }
        // 2. unspaced, ceiling -> floor (re-maximised: the setting is a ceiling)
        for (size in ceiling downTo floor step stepSp) {
            if (fits(code, size)) return Plan(code, false, floor, ceiling, size, true)
        }
        // 3. unspaced, below the floor -> last resort
        for (size in (floor - stepSp) downTo lastResort step stepSp) {
            if (fits(code, size)) return Plan(code, false, lastResort, ceiling, size, true)
        }
        // 4. nothing fits: draw it anyway, complete, past the slot
        return Plan(code, false, lastResort, ceiling, lastResort, false)
    }
}
