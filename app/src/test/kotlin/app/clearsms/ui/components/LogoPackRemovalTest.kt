package app.clearsms.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * The user-supplied "Sender logo pack" feature was removed once artwork
 * started shipping in the APK. These tests keep it removed: no source file
 * may reference the pack loader, its SharedPreferences store or its import
 * flow, and the avatar chain must not contain a user-logo step.
 */
class LogoPackRemovalTest {
    @Test
    fun `no main source references the removed logo pack`() {
        val forbidden = listOf("LogoPack", "sender_logo_pack", "settings_logo_pack", "importZip")
        val offenders =
            mainSourceFiles()
                .flatMap { file ->
                    val text = file.readText()
                    forbidden.filter { it in text }.map { "${file.name}: $it" }
                }
        assertThat(offenders).isEmpty()
    }

    @Test
    fun `avatar styles no longer include a user logo variant`() {
        assertThat(AvatarStyle.entries.map { it.name })
            .containsExactly("PHOTO", "BUNDLED", "BRAND", "BRAND_MARK", "PLAIN")
            .inOrder()
    }

    @Test
    fun `resolution chain is contact photo, bundled logo, brand tile, glyph, letter`() {
        // With everything available, each step wins in order as it drops out.
        var style =
            avatarStyleFor(
                richAvatars = true,
                photoUri = "content://p/1",
                isKnownSender = true,
                hasBundledLogo = true,
                hasBrand = true,
            )
        assertThat(style).isEqualTo(AvatarStyle.PHOTO)
        style = avatarStyleFor(true, null, true, hasBundledLogo = true, hasBrand = true)
        assertThat(style).isEqualTo(AvatarStyle.BUNDLED)
        style = avatarStyleFor(true, null, true, hasBundledLogo = false, hasBrand = true)
        assertThat(style).isEqualTo(AvatarStyle.BRAND)
        style = avatarStyleFor(true, null, true, hasBundledLogo = false, hasBrand = false)
        assertThat(style).isEqualTo(AvatarStyle.BRAND_MARK)
        style = avatarStyleFor(true, null, false, hasBundledLogo = false, hasBrand = false)
        assertThat(style).isEqualTo(AvatarStyle.PLAIN)
    }

    private fun mainSourceFiles(): List<File> {
        val mainDir = File(System.getProperty("user.dir"), "src/main")
        check(mainDir.isDirectory) { "src/main not found from ${System.getProperty("user.dir")}" }
        return mainDir
            .walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .filterNot { "assets" in it.path }
            .toList()
    }
}
