package app.clearsms.ui.theme

import androidx.compose.ui.graphics.Color
import app.clearsms.ui.components.contrastRatio
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * The three semantic amount colors are fixed by design (financial meaning
 * must never shift with the wallpaper): these tests pin the exact values,
 * assert WCAG AA contrast on both light and dark surfaces (app surfaces and
 * typical notification shades), and keep the Compose copy identical to the
 * `amount_*` entries in colors.xml used by the notification layout.
 */
class SemanticAmountColorsTest {
    @Test
    fun `light variant pins the exact fixed values`() {
        assertThat(SemanticAmountColors.Light.debit).isEqualTo(Color(0xFFB3261E))
        assertThat(SemanticAmountColors.Light.credit).isEqualTo(Color(0xFF1B5E20))
        assertThat(SemanticAmountColors.Light.balance).isEqualTo(Color(0xFF0D47A1))
    }

    @Test
    fun `dark variant pins the exact fixed values`() {
        assertThat(SemanticAmountColors.Dark.debit).isEqualTo(Color(0xFFFFB4AB))
        assertThat(SemanticAmountColors.Dark.credit).isEqualTo(Color(0xFFA5D6A7))
        assertThat(SemanticAmountColors.Dark.balance).isEqualTo(Color(0xFF90CAF9))
    }

    @Test
    fun `light variant meets WCAG AA on light surfaces`() {
        val lightBackgrounds =
            listOf(
                Color(0xFFFFFFFF), // white notification shade
                Color(0xFFF4FBF8), // app light surface (Theme.kt fallback)
                Color(0xFF74F8E5), // app light primaryContainer (summary card)
            )
        for (bg in lightBackgrounds) {
            for (color in SemanticAmountColors.Light.all()) {
                assertThat(contrastRatio(bg, color)).isAtLeast(4.5)
            }
        }
    }

    @Test
    fun `dark variant meets WCAG AA on dark surfaces`() {
        val darkBackgrounds =
            listOf(
                Color(0xFF121212), // typical dark notification shade
                Color(0xFF0E1513), // app dark surface (Theme.kt fallback)
                Color(0xFF005048), // app dark primaryContainer (summary card)
            )
        for (bg in darkBackgrounds) {
            for (color in SemanticAmountColors.Dark.all()) {
                assertThat(contrastRatio(bg, color)).isAtLeast(4.5)
            }
        }
    }

    @Test
    fun `compose light values match the colors-xml notification values`() {
        val xml = xmlColors("src/main/res/values/colors.xml")
        assertThat(xml["amount_debit"]).isEqualTo(SemanticAmountColors.Light.debit)
        assertThat(xml["amount_credit"]).isEqualTo(SemanticAmountColors.Light.credit)
        assertThat(xml["amount_balance"]).isEqualTo(SemanticAmountColors.Light.balance)
    }

    @Test
    fun `compose dark values match the values-night notification values`() {
        val xml = xmlColors("src/main/res/values-night/colors.xml")
        assertThat(xml["amount_debit"]).isEqualTo(SemanticAmountColors.Dark.debit)
        assertThat(xml["amount_credit"]).isEqualTo(SemanticAmountColors.Dark.credit)
        assertThat(xml["amount_balance"]).isEqualTo(SemanticAmountColors.Dark.balance)
    }

    private fun SemanticAmountColors.all() = listOf(debit, credit, balance)

    /** Parses `<color name="…">#AARRGGBB</color>` entries from a res XML file. */
    private fun xmlColors(moduleRelativePath: String): Map<String, Color> {
        val file =
            sequenceOf(File(moduleRelativePath), File("app", moduleRelativePath))
                .firstOrNull(File::exists)
                ?: error("Cannot locate $moduleRelativePath from ${File(".").absolutePath}")
        return COLOR_ENTRY
            .findAll(file.readText())
            .associate { match ->
                val hex = match.groupValues[2]
                val argb = if (hex.length == 8) hex.toLong(16) else 0xFF000000L or hex.toLong(16)
                match.groupValues[1] to Color(argb)
            }
    }

    private companion object {
        val COLOR_ENTRY = Regex("""<color name="([^"]+)">#([0-9A-Fa-f]{6,8})</color>""")
    }
}
