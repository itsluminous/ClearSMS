package app.clearsms.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContract

/**
 * Picks the intent that will actually open a camera for a capture into
 * [output] (issue #140).
 *
 * `ActivityResultContracts.TakePicture` fires a bare implicit
 * `ACTION_IMAGE_CAPTURE`. Since Android 11 the platform resolves that
 * implicit action against PRE-INSTALLED SYSTEM camera apps only
 * (`PackageManagerService.updateFlagsForResolve` adds MATCH_SYSTEM_ONLY
 * for an implicit image-capture intent). A camera app the user installed
 * - the Play-store Pixel Camera on a de-Googled Pixel, Open Camera from
 * F-Droid on a ROM that ships no camera - is therefore never found, and
 * the start fails with ActivityNotFoundException even though the camera
 * "is installed and working". Package visibility is NOT what fails here:
 * an implicit activity start is resolved regardless of `<queries>`.
 *
 * The platform's sanctioned way round the rule is an EXPLICIT intent:
 * `setPackage(camera)`. So this helper tries the implicit intent first
 * (the stock path, unchanged on a device with a system camera), and only
 * when nothing answers it looks for installed camera apps - the ones that
 * declare `CATEGORY_APP_CAMERA`, the platform's own definition of "a camera
 * app" - and addresses the capture to each one that handles
 * `IMAGE_CAPTURE`. Those two look-ups DO need `<queries>` visibility, which
 * the manifest declares next to the carrier-service entry. When no camera
 * answers at all the implicit intent is returned as-is, so the launch still
 * raises ActivityNotFoundException and the compose bar's "No camera app
 * available" toast keeps working.
 */
object CameraCapture {
    /**
     * `Intent.CATEGORY_APP_CAMERA` - the category the lock screen's camera
     * shortcut launches; `@hide` in the SDK stubs, hence spelled out.
     */
    const val CATEGORY_APP_CAMERA = "android.intent.category.APP_CAMERA"

    /** Whether the platform would start [intent] - the one seam a unit test fakes. */
    fun interface Resolver {
        fun resolves(intent: Intent): Boolean
    }

    fun platformResolver(pm: PackageManager): Resolver =
        Resolver { intent -> pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null }

    /** The plain implicit capture intent `TakePicture` would have fired. */
    fun implicitIntent(output: Uri): Intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, output)

    /**
     * Installed camera apps: packages with an activity in
     * `CATEGORY_APP_CAMERA` (what the lock screen and launcher open). Needs
     * the matching `<queries>` entry on Android 11+.
     */
    fun cameraPackages(pm: PackageManager): List<String> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(CATEGORY_APP_CAMERA)
        return pm
            .queryIntentActivities(launcher, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
    }

    /**
     * The intent to launch for a capture into [output]: the implicit one
     * when a system camera answers it, else an explicit one per installed
     * camera that handles `IMAGE_CAPTURE` (a chooser when several do), else
     * the implicit one again so the caller's ActivityNotFoundException
     * handling runs unchanged.
     */
    fun intentFor(
        output: Uri,
        cameraPackages: List<String>,
        resolver: Resolver,
    ): Intent {
        val implicit = implicitIntent(output)
        if (resolver.resolves(implicit)) return implicit
        val explicit =
            cameraPackages
                .map { pkg -> Intent(implicit).setPackage(pkg) }
                .filter(resolver::resolves)
        return when (explicit.size) {
            0 -> {
                implicit
            }

            1 -> {
                explicit.single()
            }

            else -> {
                Intent
                    .createChooser(explicit.first(), null)
                    .putExtra(Intent.EXTRA_INITIAL_INTENTS, explicit.drop(1).toTypedArray())
            }
        }
    }

    fun intentFor(
        context: Context,
        output: Uri,
    ): Intent = intentFor(output, cameraPackages(context.packageManager), platformResolver(context.packageManager))
}

/**
 * `TakePicture` with [CameraCapture]'s resolution: same input (the output
 * URI), same result (whether a picture was written), same exception when
 * no camera exists at all.
 */
class TakePictureWithAnyCamera : ActivityResultContract<Uri, Boolean>() {
    override fun createIntent(
        context: Context,
        input: Uri,
    ): Intent = CameraCapture.intentFor(context, input)

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): Boolean = resultCode == Activity.RESULT_OK
}
