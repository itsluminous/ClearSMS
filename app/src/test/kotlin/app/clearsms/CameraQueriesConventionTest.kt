package app.clearsms

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Source contract for the compose bar's Camera option (issue #140).
 *
 * The composer must launch through [app.clearsms.ui.components.TakePictureWithAnyCamera],
 * not the stock `TakePicture`: the stock contract fires a bare implicit
 * IMAGE_CAPTURE, which Android 11+ answers with pre-installed system cameras
 * only, so an installed camera app is never found. And as long as
 * CameraCapture discovers cameras by querying the package manager, the
 * manifest's `<queries>` block must grant visibility for exactly those two
 * look-ups - without it the queries return nothing on Android 11+ and the
 * fallback silently degrades to the old behaviour. The failure path (the
 * "No camera app available" toast on a device with no camera at all) must
 * stay a caught ActivityNotFoundException.
 */
class CameraQueriesConventionTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val composer = File("src/main/kotlin/app/clearsms/ui/components/ComposerAttachments.kt").readText()
    private val capture = File("src/main/kotlin/app/clearsms/ui/components/CameraCapture.kt").readText()

    private fun queries(): String = manifest.substringAfter("<queries>").substringBefore("</queries>")

    @Test
    fun `the composer launches the camera through TakePictureWithAnyCamera`() {
        assertThat(composer).contains("rememberLauncherForActivityResult(TakePictureWithAnyCamera())")
        assertThat(composer).doesNotContain("ActivityResultContracts.TakePicture()")
    }

    @Test
    fun `IMAGE_CAPTURE is declared visible as long as the camera is resolved by query`() {
        assertThat(capture).contains("MediaStore.ACTION_IMAGE_CAPTURE")
        assertThat(capture).contains("pm.resolveActivity(")
        assertThat(queries()).contains("""<action android:name="android.media.action.IMAGE_CAPTURE" />""")
    }

    @Test
    fun `APP_CAMERA is declared visible as long as camera apps are discovered by it`() {
        assertThat(capture).contains("addCategory(CATEGORY_APP_CAMERA)")
        val cameraLauncherEntry =
            Regex("""<intent>.*?</intent>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(queries())
                .map { it.value }
                .firstOrNull {
                    it.contains("""<action android:name="android.intent.action.MAIN" />""") &&
                        it.contains("""<category android:name="android.intent.category.APP_CAMERA" />""")
                }
        assertThat(cameraLauncherEntry).isNotNull()
    }

    @Test
    fun `no camera at all still ends in the caught toast, never a crash`() {
        val takePhoto = composer.substringAfter("takePhoto = {").substringBefore("pickFile = {")
        assertThat(takePhoto).contains("catch (_: ActivityNotFoundException)")
        assertThat(takePhoto).contains("cameraMissing")
        assertThat(composer).contains("stringResource(R.string.compose_camera_missing)")
        // Nothing resolving must hand the implicit intent back untouched, so
        // the launch is what throws.
        assertThat(capture).containsMatch(Regex("""0 ->\s*\{?\s*implicit""").pattern)
    }

    @Test
    fun `visibility is declared per intent, never with QUERY_ALL_PACKAGES`() {
        assertThat(manifest).doesNotContain("QUERY_ALL_PACKAGES")
    }
}
