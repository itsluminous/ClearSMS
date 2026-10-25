package app.clearsms.ui.components

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Camera-intent resolution for the compose bar (issue #140). The resolver
 * fakes the Android 11+ platform rule - an IMPLICIT image-capture intent is
 * answered by pre-installed system cameras only, an explicit one by
 * whichever package it names - since Robolectric knows nothing of it.
 */
@RunWith(RobolectricTestRunner::class)
class CameraCaptureTest {
    private val output: Uri = Uri.parse("content://app.clearsms.fileprovider/attachments/capture.jpg")

    /** [systemCamera] answers the implicit intent; [installed] answer only when named. */
    private fun platform(
        systemCamera: Boolean,
        installed: Set<String> = emptySet(),
    ) = CameraCapture.Resolver { intent ->
        intent.action == MediaStore.ACTION_IMAGE_CAPTURE &&
            (if (intent.`package` == null) systemCamera else intent.`package` in installed)
    }

    @Test
    fun `a system camera keeps the stock implicit intent, output attached`() {
        val intent = CameraCapture.intentFor(output, listOf("org.example.cam"), platform(systemCamera = true))
        assertThat(intent.action).isEqualTo(MediaStore.ACTION_IMAGE_CAPTURE)
        assertThat(intent.`package`).isNull()
        assertThat(intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)).isEqualTo(output)
    }

    @Test
    fun `an installed non-system camera is addressed explicitly`() {
        val intent =
            CameraCapture.intentFor(
                output,
                listOf("org.example.cam"),
                platform(systemCamera = false, installed = setOf("org.example.cam")),
            )
        assertThat(intent.action).isEqualTo(MediaStore.ACTION_IMAGE_CAPTURE)
        assertThat(intent.`package`).isEqualTo("org.example.cam")
        assertThat(intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)).isEqualTo(output)
    }

    @Test
    fun `a camera app that does not handle IMAGE_CAPTURE is skipped`() {
        val intent =
            CameraCapture.intentFor(
                output,
                listOf("org.example.viewer", "org.example.cam"),
                platform(systemCamera = false, installed = setOf("org.example.cam")),
            )
        assertThat(intent.`package`).isEqualTo("org.example.cam")
    }

    @Test
    fun `several installed cameras become a chooser carrying every one`() {
        val intent =
            CameraCapture.intentFor(
                output,
                listOf("org.a.cam", "org.b.cam"),
                platform(systemCamera = false, installed = setOf("org.a.cam", "org.b.cam")),
            )
        assertThat(intent.action).isEqualTo(Intent.ACTION_CHOOSER)
        val target = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        val others = intent.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS)!!.map { it as Intent }
        assertThat((listOf(target) + others).map { it.`package` }).containsExactly("org.a.cam", "org.b.cam")
        assertThat(target.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)).isEqualTo(output)
    }

    @Test
    fun `no camera at all leaves the implicit intent so the launch still throws`() {
        // The compose bar catches ActivityNotFoundException from this launch
        // and shows "No camera app available"; a null or synthetic intent
        // would break that contract.
        val intent = CameraCapture.intentFor(output, emptyList(), platform(systemCamera = false))
        assertThat(intent.action).isEqualTo(MediaStore.ACTION_IMAGE_CAPTURE)
        assertThat(intent.`package`).isNull()
        assertThat(intent.component).isNull()
    }

    @Test
    fun `the platform resolver honours an explicit package on a capture intent`() {
        val pm = ApplicationProvider.getApplicationContext<android.content.Context>().packageManager
        val component = ComponentName("org.example.cam", "org.example.cam.Capture")
        shadowOf(pm).addActivityIfNotPresent(component)
        shadowOf(pm).addIntentFilterForActivity(
            component,
            IntentFilter(MediaStore.ACTION_IMAGE_CAPTURE).apply { addCategory(Intent.CATEGORY_DEFAULT) },
        )
        val resolver = CameraCapture.platformResolver(pm)
        assertThat(resolver.resolves(Intent(CameraCapture.implicitIntent(output)).setPackage("org.example.cam"))).isTrue()
        assertThat(resolver.resolves(Intent(CameraCapture.implicitIntent(output)).setPackage("org.example.other"))).isFalse()
    }

    @Test
    fun `camera packages are the APP_CAMERA activities, each package once`() {
        val pm = ApplicationProvider.getApplicationContext<android.content.Context>().packageManager
        val filter =
            IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(CameraCapture.CATEGORY_APP_CAMERA)
                addCategory(Intent.CATEGORY_DEFAULT)
            }

        fun install(
            component: ComponentName,
            intentFilter: IntentFilter,
        ) {
            shadowOf(pm).addActivityIfNotPresent(component)
            shadowOf(pm).addIntentFilterForActivity(component, intentFilter)
        }
        install(ComponentName("org.example.cam", "org.example.cam.Main"), filter)
        install(ComponentName("org.example.cam", "org.example.cam.Secure"), filter)
        install(
            ComponentName("org.example.gallery", "org.example.gallery.Main"),
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_GALLERY) },
        )
        assertThat(CameraCapture.cameraPackages(pm)).containsExactly("org.example.cam")
    }
}
