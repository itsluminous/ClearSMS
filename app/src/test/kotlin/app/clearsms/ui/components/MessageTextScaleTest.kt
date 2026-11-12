package app.clearsms.ui.components

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.sp
import app.clearsms.domain.model.MessageTextSize
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * GitHub #157: the bubble-text multiplier is a STYLE-level scale that stays
 * in sp, so it multiplies Android's own font-size setting instead of
 * replacing it, and it never touches `LocalDensity`.
 */
class MessageTextScaleTest {
    private fun source(path: String) = File("src/main/kotlin/app/clearsms/$path").readText()

    private val bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)

    @Test
    fun `the steps are Small Default Large Extra large`() {
        assertThat(MessageTextSize.entries.map { it.scale }).containsExactly(0.85f, 1f, 1.15f, 1.3f).inOrder()
        assertThat(MessageTextSize.DEFAULT.scale).isEqualTo(1f)
    }

    @Test
    fun `scaling keeps the unit in sp - so the system font scale still multiplies it at layout`() {
        val scaled = bodyLarge.scaledBy(MessageTextSize.EXTRA_LARGE.scale)
        assertThat(scaled.fontSize.type).isEqualTo(TextUnitType.Sp)
        assertThat(scaled.lineHeight.type).isEqualTo(TextUnitType.Sp)
        // 16/24 at 1.3 is 20.8/31.2: font and line height scale together, so
        // the body stays proportioned instead of cramped or airy.
        assertThat(scaled.fontSize.value).isWithin(0.001f).of(20.8f)
        assertThat(scaled.lineHeight.value).isWithin(0.001f).of(31.2f)
        // Multiplicative by construction: a system scale of 1.5 on top gives
        // 1.3 x 1.5 of the base, never 1.3 instead of 1.5.
        val systemFontScale = 1.5f
        assertThat(scaled.fontSize.value * systemFontScale).isWithin(0.001f).of(16f * 1.3f * 1.5f)
    }

    @Test
    fun `every step scales both dimensions and Default is the identity`() {
        assertThat(bodyLarge.scaledBy(MessageTextSize.DEFAULT.scale)).isSameInstanceAs(bodyLarge)
        for (size in MessageTextSize.entries) {
            val scaled = bodyLarge.scaledBy(size.scale)
            assertThat(scaled.fontSize.value).isWithin(0.001f).of(16f * size.scale)
            assertThat(scaled.lineHeight.value).isWithin(0.001f).of(24f * size.scale)
        }
    }

    @Test
    fun `unspecified sizes are left alone rather than multiplied into an error`() {
        val bare = TextStyle()
        val scaled = bare.scaledBy(1.3f)
        assertThat(scaled.fontSize).isEqualTo(TextUnit.Unspecified)
        assertThat(scaled.lineHeight).isEqualTo(TextUnit.Unspecified)
    }

    @Test
    fun `the multiplier is applied to the bubble body style only - never to LocalDensity`() {
        val conversation = source("ui/conversation/ConversationScreen.kt")
        // Exactly one reader: the bubble's bodyStyle. The title, time/tick/SIM
        // row, pills, OTP banner and composer keep their own sizes.
        assertThat(Regex("LocalMessageTextScale\\.current").findAll(conversation).count()).isEqualTo(1)
        assertThat(conversation).contains(".scaledBy(LocalMessageTextScale.current)")
        // A density override would scale everything in the subtree and feed
        // back into ConversationTitleFit (sp steps against the density it is
        // handed) and OtpDigitSizes; the OTP banner's RemoteViews are
        // outside Compose anyway. Nothing in the app may provide one.
        val main = File("src/main/kotlin/app/clearsms")
        val densityOverrides =
            main
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { it.readText().contains("LocalDensity provides") || it.readText().contains("fontScale =") }
                .map { it.name }
                .toList()
        assertThat(densityOverrides).isEmpty()
        // Provided once at the root, from the backed-up preference.
        assertThat(source("ui/navigation/ClearSmsApp.kt")).contains("LocalMessageTextScale provides state.messageTextSize.scale")
    }

    @Test
    fun `the inbox preview is deliberately out of scope - the two-line clamp would only show fewer words`() {
        assertThat(source("ui/inbox/InboxScreen.kt")).doesNotContain("LocalMessageTextScale")
        assertThat(source("ui/inbox/BinScreen.kt")).doesNotContain("LocalMessageTextScale")
        assertThat(source("ui/inbox/ArchivedScreen.kt")).doesNotContain("LocalMessageTextScale")
    }
}
