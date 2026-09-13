package app.clearsms.ui.components

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import app.clearsms.TestClearSmsApplication
import coil3.ImageLoader
import coil3.decode.BitmapFactoryDecoder
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The image pipeline after the Coil 3 migration (which removed OkHttp from
 * the APK - Coil 3's core has no network dependency; HTTP support lives in
 * the deliberately-not-included coil-network-okhttp artifact).
 *
 * This app loads images from exactly two model shapes, and both must keep
 * resolving through the default (network-free) ImageLoader's fetchers:
 *  - [File]: MMS bubbles + full-screen viewer (MmsAttachments.kt) and
 *    composer attachment previews (ComposerAttachments.kt)
 *  - content:// URI strings: contact photos in the avatar chain
 *    (SenderAvatar.kt)
 *
 * The requests pin [BitmapFactoryDecoder] because Robolectric cannot run the
 * ImageDecoder-based StaticImageDecoder - the decoder is a test-environment
 * substitution; the fetch/map path under test is exactly production's.
 *
 * And the failure mode for a network URL must be Coil's normal [ErrorResult]
 * - never a linkage error like NoClassDefFoundError, which is what a
 * half-removed HTTP client would produce at runtime.
 */
@RunWith(RobolectricTestRunner::class)
class ImageDecodePipelineTest {
    private lateinit var app: TestClearSmsApplication
    private lateinit var imageLoader: ImageLoader

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        // Same construction path production uses (SingletonImageLoader's
        // default factory builds ImageLoader(context) with default
        // components); caches disabled so each test decodes for real.
        imageLoader = ImageLoader.Builder(app).diskCache(null).build()
    }

    @After
    fun tearDown() {
        imageLoader.shutdown()
    }

    private fun request(model: Any): ImageRequest =
        ImageRequest
            .Builder(app)
            .data(model)
            // Robolectric native graphics has no hardware bitmaps and no
            // working android.graphics.ImageDecoder.
            .allowHardware(false)
            .decoderFactory(BitmapFactoryDecoder.Factory())
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()

    @Test
    fun `File model decodes - MMS bubbles, viewer and composer previews`() {
        val file = File.createTempFile("clearsms-mms", ".png").apply { writeBytes(pngBytes()) }
        try {
            val result = runBlocking { imageLoader.execute(request(file)) }
            assertWithMessage("File decode must succeed: $result")
                .that(result)
                .isInstanceOf(SuccessResult::class.java)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `content uri model decodes - contact photos in the avatar chain`() {
        Robolectric.setupContentProvider(PngProvider::class.java, PngProvider.AUTHORITY)
        // SenderAvatar passes the photo URI as a String model, so test that shape.
        val result = runBlocking { imageLoader.execute(request("content://${PngProvider.AUTHORITY}/photo/42")) }
        assertWithMessage("content:// decode must succeed: $result")
            .that(result)
            .isInstanceOf(SuccessResult::class.java)
    }

    @Test
    fun `network url fails with ErrorResult - never a linkage error`() {
        val result = runBlocking { imageLoader.execute(request("https://example.invalid/logo.png")) }
        assertThat(result).isInstanceOf(ErrorResult::class.java)
        val cause = (result as ErrorResult).throwable
        assertWithMessage("a missing network fetcher must surface as a normal error, got $cause")
            .that(cause)
            .isNotInstanceOf(LinkageError::class.java)
    }

    @Test
    fun `OkHttp is not on the runtime classpath at all`() {
        // The strongest reachability proof: the class simply does not exist,
        // so no code path - lazy or otherwise - can ever load it.
        try {
            Class.forName("okhttp3.OkHttpClient")
            throw AssertionError("okhttp3.OkHttpClient is on the classpath - the HTTP client is back")
        } catch (_: ClassNotFoundException) {
            // expected: fully offline app, no HTTP client
        }
    }

    /**
     * Minimal provider serving a valid PNG for any URI, the way a contacts
     * photo provider would - Coil's ContentUriFetcher opens content URIs via
     * openAssetFileDescriptor, which needs a real registered provider.
     */
    class PngProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun openFile(
            uri: Uri,
            mode: String,
        ): ParcelFileDescriptor {
            val file = File.createTempFile("clearsms-avatar", ".png").apply { writeBytes(pngBytes()) }
            file.deleteOnExit()
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun getType(uri: Uri): String = "image/png"

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        companion object {
            const val AUTHORITY = "app.clearsms.test.avatarphotos"
        }
    }

    private companion object {
        fun pngBytes(): ByteArray =
            ByteArrayOutputStream().use { out ->
                Bitmap
                    .createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                    .apply { eraseColor(android.graphics.Color.RED) }
                    .compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
    }
}
