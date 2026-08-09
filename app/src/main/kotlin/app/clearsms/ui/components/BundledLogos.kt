package app.clearsms.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Asset directory holding the bundled sender logo artwork. */
internal const val BUNDLED_LOGO_DIR = "logos"

/** Image file extensions accepted as logo artwork. */
private val LOGO_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

/**
 * Returns the lookup key for a logo file name - the lowercase base name -
 * or null when the extension isn't a supported image format. `HDFC.PNG`,
 * `hdfc.png` and `hdfc.webp` all key to `hdfc`.
 */
fun logoKeyForFileName(fileName: String): String? {
    val dot = fileName.lastIndexOf('.')
    if (dot <= 0) return null
    val ext = fileName.substring(dot + 1).lowercase()
    if (ext !in LOGO_EXTENSIONS) return null
    return fileName.substring(0, dot).trim().lowercase()
}

/**
 * Builds the set of bundled-logo keys from an asset directory listing.
 * Only PNG files count (the directory also carries a provenance
 * `MANIFEST.md`, which must never become a lookup key).
 */
fun bundledLogoKeys(fileNames: List<String>): Set<String> =
    fileNames
        .filter { it.endsWith(".png", ignoreCase = true) }
        .mapNotNull(::logoKeyForFileName)
        .toSet()

/**
 * A decode-once cache: [load] runs at most once per key - successes and
 * failures alike are memoized, so a corrupt or missing asset costs one
 * attempt and then falls back forever instead of retrying every frame.
 * A throwing loader is treated as a miss, never propagated.
 */
class BundledLogoCache<T : Any>(
    private val load: (String) -> T?,
) {
    private val cache = ConcurrentHashMap<String, Any>()

    fun get(key: String): T? {
        val value =
            cache.computeIfAbsent(key) { k ->
                runCatching { load(k) }.getOrNull() ?: Miss
            }
        @Suppress("UNCHECKED_CAST")
        return if (value === Miss) null else value as T
    }

    private object Miss
}

/**
 * Bundled sender logos shipped in `assets/logos/` (provenance in the
 * sibling MANIFEST.md and NOTICE). Files are named `<brandKey>.png`, so
 * resolution is keyed by the curated brand table. Listing and decoding
 * happen on [Dispatchers.IO] - never on the main thread - and every
 * bitmap is decoded once per process via [BundledLogoCache].
 */
object BundledLogos {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val keysFlow = MutableStateFlow<Set<String>>(emptySet())

    @Volatile
    private var listed = false

    /** Brand keys with a bundled logo (empty until the async listing lands). */
    val keys: StateFlow<Set<String>> = keysFlow

    private var bitmaps = BundledLogoCache<ImageBitmap> { null }

    /** Lists the asset directory once, off the main thread. */
    fun ensureLoaded(context: Context) {
        if (listed) return
        listed = true
        val appContext = context.applicationContext
        bitmaps =
            BundledLogoCache { key ->
                appContext.assets.open("$BUNDLED_LOGO_DIR/$key.png").use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            }
        scope.launch {
            keysFlow.value =
                runCatching {
                    bundledLogoKeys(
                        appContext.assets
                            .list(BUNDLED_LOGO_DIR)
                            .orEmpty()
                            .toList(),
                    )
                }.getOrDefault(emptySet())
        }
    }

    /** Decodes (or returns the cached) logo bitmap for [key] on IO. */
    suspend fun bitmap(key: String): ImageBitmap? = withContext(Dispatchers.IO) { bitmaps.get(key) }
}
