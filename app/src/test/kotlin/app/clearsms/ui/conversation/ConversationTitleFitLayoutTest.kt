package app.clearsms.ui.conversation

import android.content.Context
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.MultiParagraph
import androidx.compose.ui.text.TextLayoutInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.constrain
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.ceil

/**
 * [ConversationTitleFit.autoSize]'s search over REAL text layout: the same
 * `MultiParagraph` Compose lays the title out with, under the bar's own
 * constraints, at font scales 1.0 / 1.5 / 2.0. No Compose UI harness (repo
 * convention) - the text engine alone, which is where the fit is decided.
 * `ConversationTitleFitTest` holds the same search against the height
 * model; this is the check that the model and the engine agree where the
 * contract makes a promise, and that the promise the defect broke - a
 * one-line name renders at the full title size at every scale - holds in
 * the engine's own terms.
 */
@RunWith(RobolectricTestRunner::class)
class ConversationTitleFitLayoutTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val resolver = createFontFamilyResolver(context)

    /** The app's titleLarge (SemiBold 22sp on a 28sp line) through the contract's style(). */
    private val barTitle =
        TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp)

    /** A 420dpi phone; the dp arithmetic does not depend on it. */
    private fun density(fontScale: Float) = Density(2.625f, fontScale)

    /** Lays the name out exactly as the title slot does: two lines, ellipsis, the bar's height cap. */
    private fun layout(
        name: String,
        fontSize: TextUnit,
        density: Density,
        widthDp: Int,
    ): TextLayoutResult {
        val style = ConversationTitleFit.style(barTitle).copy(fontSize = fontSize)
        val constraints =
            with(density) {
                Constraints(maxWidth = widthDp.dp.roundToPx(), maxHeight = ConversationTitleFit.MaxHeight.roundToPx())
            }
        val text = AnnotatedString(name)
        val paragraph =
            MultiParagraph(
                annotatedString = text,
                style = style,
                constraints = constraints,
                density = density,
                fontFamilyResolver = resolver,
                maxLines = ConversationTitleFit.MaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        return TextLayoutResult(
            layoutInput =
                TextLayoutInput(
                    text = text,
                    style = style,
                    placeholders = emptyList(),
                    maxLines = ConversationTitleFit.MaxLines,
                    softWrap = true,
                    overflow = TextOverflow.Ellipsis,
                    density = density,
                    layoutDirection = LayoutDirection.Ltr,
                    fontFamilyResolver = resolver,
                    constraints = constraints,
                ),
            multiParagraph = paragraph,
            size = constraints.constrain(IntSize(ceil(paragraph.width).toInt(), ceil(paragraph.height).toInt())),
        )
    }

    /** What the sizer picks for [name] in a title slot [widthDp] wide at [fontScale]. */
    private fun pick(
        name: String,
        fontScale: Float,
        widthDp: Int,
    ): TextUnit =
        ConversationTitleFit.largestFittingSize(
            ConversationTitleFit.MinFontSize,
            ConversationTitleFit.maxFontSize(barTitle.fontSize),
            ConversationTitleFit.StepSize,
        ) { size -> ConversationTitleFit.fitsWithoutEllipsis(layout(name, size, density(fontScale), widthDp)) }

    @Test
    fun `a one-line name renders at the full title size at every font scale`() {
        // A 10-character name in a title slot a phone leaves it (the measured
        // defect's case): one line at 22sp, no shrink, at every scale -
        // including the 1.5 where StepBased returned 20sp for it.
        for (scale in listOf(1.0f, 1.15f, 1.3f, 1.5f, 1.8f, 2.0f)) {
            val picked = pick("Jane Smith", scale, widthDp = 190)
            assertWithMessage("scale $scale").that(picked).isEqualTo(22.sp)
            val laidOut = layout("Jane Smith", picked, density(scale), widthDp = 190)
            assertWithMessage("scale $scale lines").that(laidOut.lineCount).isEqualTo(1)
            assertWithMessage("scale $scale ellipsis").that(laidOut.isLineEllipsized(0)).isFalse()
        }
    }

    @Test
    fun `a two-line name shrinks only when its two lines do not fit, and never below the floor`() {
        // Needs two lines at every size in a 260dp slot, so the fit is purely
        // the height question - the case the arithmetic test models.
        val name = "Krishnamurthy Venkataraman"
        fun lines(
            size: TextUnit,
            scale: Float,
        ) = layout(name, size, density(scale), widthDp = 260)
        assertThat(lines(22.sp, 1.0f).lineCount).isEqualTo(2)
        // Scale 1.0: both lines fit at the full size (52.8dp of 64).
        assertThat(pick(name, 1.0f, widthDp = 260)).isEqualTo(22.sp)
        // Scale 1.5: 22sp's two lines (27dp each) overflow 64dp, so the
        // height-aware ellipsis keeps one line of it - which the sizer reads
        // as "does not fit" - and 21sp (26.5dp) is the largest whose two
        // lines fit: one step down, not a dropped line and not the floor.
        // The model agrees exactly here (linesFit: 64.8dp fails, 63.6dp fits).
        val at15 = lines(22.sp, 1.5f)
        assertThat(at15.lineCount).isEqualTo(1)
        assertThat(at15.isLineEllipsized(0)).isTrue()
        assertThat(ConversationTitleFit.fitsWithoutEllipsis(at15)).isFalse()
        assertThat(pick(name, 1.5f, widthDp = 260)).isEqualTo(21.sp)
        assertThat(ConversationTitleFit.linesFit(22.sp, 2, density(1.5f))).isFalse()
        assertThat(ConversationTitleFit.linesFit(21.sp, 2, density(1.5f))).isTrue()
        val picked15 = lines(21.sp, 1.5f)
        assertThat(picked15.lineCount).isEqualTo(2)
        assertThat(picked15.isLineEllipsized(1)).isFalse()
        // Scale 2.0: even the floor's two lines (28dp each) overflow, so the
        // sizer stops AT the floor - 16sp, never less - and ellipsis keeps the
        // name to the one line that fits.
        val picked20 = pick(name, 2.0f, widthDp = 260)
        assertThat(picked20).isEqualTo(ConversationTitleFit.MinFontSize)
        assertThat(picked20.isSp).isTrue()
        val floor20 = lines(ConversationTitleFit.MinFontSize, 2.0f)
        assertThat(floor20.lineCount).isEqualTo(1)
        assertThat(floor20.isLineEllipsized(0)).isTrue()
        assertThat(ConversationTitleFit.linesFit(ConversationTitleFit.MinFontSize, 2, density(2.0f))).isFalse()
    }

    @Test
    fun `fitsWithoutEllipsis reads the last kept line, whichever axis overflowed`() {
        // Width overflow: a name longer than two lines hold at 16sp is
        // ellipsised on its second line.
        val tooLong = "Sri Venkateswara Temple Trust Hyderabad Office Front Desk Reception"
        val wide = layout(tooLong, 16.sp, density(1.0f), widthDp = 190)
        assertThat(wide.lineCount).isEqualTo(2)
        assertThat(wide.isLineEllipsized(1)).isTrue()
        assertThat(ConversationTitleFit.fitsWithoutEllipsis(wide)).isFalse()
        // Height overflow: two lines that do not fit 64dp collapse to one
        // ellipsised line (height-aware ellipsis), read the same way.
        val tall = layout("Krishnamurthy Venkataraman", 22.sp, density(2.0f), widthDp = 260)
        assertThat(tall.lineCount).isEqualTo(1)
        assertThat(ConversationTitleFit.fitsWithoutEllipsis(tall)).isFalse()
        // No overflow: fits.
        val fine = layout("Jane Smith", 22.sp, density(2.0f), widthDp = 260)
        assertThat(fine.lineCount).isEqualTo(1)
        assertThat(ConversationTitleFit.fitsWithoutEllipsis(fine)).isTrue()
    }
}
