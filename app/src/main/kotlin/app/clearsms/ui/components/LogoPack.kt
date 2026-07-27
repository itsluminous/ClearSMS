package app.clearsms.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.zip.ZipInputStream

/** Image file extensions accepted in a user-supplied logo pack. */
private val LOGO_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

/** Per-file size cap — anything larger is skipped so a huge image can't blow up memory. */
internal const val LOGO_MAX_BYTES = 2L * 1024 * 1024

/** Maximum entries imported from a zip — a guard against pathological archives. */
internal const val LOGO_MAX_ZIP_ENTRIES = 500

/**
 * Returns the lookup key for a logo-pack file name — the lowercase base name —
 * or null when the extension isn't a supported image format. `HDFCBK.PNG`,
 * `hdfcbk.png` and `hdfcbk.webp` all key to `hdfcbk`.
 */
fun logoKeyForFileName(fileName: String): String? {
    val dot = fileName.lastIndexOf('.')
    if (dot <= 0) return null
    val ext = fileName.substring(dot + 1).lowercase()
    if (ext !in LOGO_EXTENSIONS) return null
    return fileName.substring(0, dot).trim().lowercase()
}

/**
 * Finds the user's logo for a sender in an [index] of logo keys, trying the
 * curated brand key first (`hdfc`), then the TRAI-normalized sender ID
 * (`VM-HDFCBK` → `hdfcbk`), then the raw sender — all case-insensitive.
 */
fun <T> resolveLogo(
    index: Map<String, T>,
    brandKey: String?,
    sender: String,
): T? {
    brandKey?.lowercase()?.let { index[it]?.let { hit -> return hit } }
    index[normalizeSenderId(sender).lowercase()]?.let { return it }
    return index[sender.trim().lowercase()]
}

/**
 * Optional, user-supplied sender logo pack (Settings → Appearance).
 *
 * The app never ships third-party logos; the user may point it at a local
 * folder (SAF tree) or import a zip of images named by brand key or sender ID
 * (`hdfc.png`, `HDFCBK.png`). Everything stays on-device: images are read via
 * the content resolver or the app's private storage — never the network.
 * The resolved index lives in memory; missing or oversized files are skipped.
 */
object LogoPack {
    private const val PREFS = "sender_logo_pack"
    private const val KEY_TREE_URI = "tree_uri"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val indexFlow = MutableStateFlow<Map<String, Uri>>(emptyMap())
    private val treeUriFlow = MutableStateFlow<String?>(null)

    @Volatile
    private var loadedFor: String? = "UNLOADED"

    /** Lowercase logo key → content/file URI for every usable image in the pack. */
    val index: StateFlow<Map<String, Uri>> = indexFlow

    /** The persisted SAF tree URI (null when no folder is configured). */
    val treeUri: StateFlow<String?> = treeUriFlow

    /** True when either a folder is linked or zip-imported logos exist. */
    fun isConfigured(context: Context): Boolean = readTreeUri(context) != null || zipDir(context).listFiles()?.isNotEmpty() == true

    /** Loads (or reloads) the index off the main thread if the config changed. */
    fun ensureLoaded(context: Context) {
        val appContext = context.applicationContext
        val current = readTreeUri(appContext)
        treeUriFlow.value = current
        if (loadedFor == current) return
        loadedFor = current
        scope.launch { indexFlow.value = buildIndex(appContext, current) }
    }

    /** Persists the user's folder choice (null clears it) and reindexes. */
    fun setTreeUri(
        context: Context,
        uri: Uri?,
    ) {
        val appContext = context.applicationContext
        if (uri != null) {
            try {
                appContext.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                // Non-persistable providers still work for this session.
            }
        }
        prefs(appContext).edit().putString(KEY_TREE_URI, uri?.toString()).apply()
        loadedFor = "RELOAD"
        ensureLoaded(appContext)
    }

    /**
     * Imports images from a user-picked zip into private storage
     * (`files/logo_pack/`) with zip-slip protection, size caps, and image-only
     * filtering, then reindexes. Returns the number of images imported.
     */
    fun importZip(
        context: Context,
        zipUri: Uri,
    ): Int {
        val appContext = context.applicationContext
        val dir = zipDir(appContext).apply { mkdirs() }
        var imported = 0
        appContext.contentResolver.openInputStream(zipUri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entries = 0
                var entry = zip.nextEntry
                while (entry != null && entries < LOGO_MAX_ZIP_ENTRIES) {
                    entries++
                    val baseName = entry.name.substringAfterLast('/')
                    val key = if (entry.isDirectory) null else logoKeyForFileName(baseName)
                    if (key != null && baseName.none { it == '\\' } && entry.size <= LOGO_MAX_BYTES) {
                        val target = File(dir, baseName)
                        if (target.canonicalPath.startsWith(dir.canonicalPath)) {
                            target.outputStream().use { out -> zip.copyTo(out, bufferSize = 8 * 1024) }
                            if (target.length() > LOGO_MAX_BYTES) target.delete() else imported++
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        loadedFor = "RELOAD"
        ensureLoaded(appContext)
        return imported
    }

    /** Removes the linked folder and every zip-imported image. */
    fun clear(context: Context) {
        val appContext = context.applicationContext
        prefs(appContext).edit().remove(KEY_TREE_URI).apply()
        zipDir(appContext).listFiles()?.forEach { it.delete() }
        loadedFor = "RELOAD"
        ensureLoaded(appContext)
    }

    private fun buildIndex(
        context: Context,
        tree: String?,
    ): Map<String, Uri> =
        buildMap {
            zipDir(context).listFiles()?.forEach { file ->
                logoKeyForFileName(file.name)?.let { key ->
                    if (file.length() in 1..LOGO_MAX_BYTES) put(key, Uri.fromFile(file))
                }
            }
            if (tree != null) indexTree(context, Uri.parse(tree), this)
        }

    private fun indexTree(
        context: Context,
        tree: Uri,
        into: MutableMap<String, Uri>,
    ) {
        try {
            val childrenUri =
                DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree,
                    DocumentsContract.getTreeDocumentId(tree),
                )
            context.contentResolver
                .query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                    ),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val docId = cursor.getString(0)
                        val name = cursor.getString(1) ?: continue
                        val mime = cursor.getString(2) ?: ""
                        val size = cursor.getLong(3)
                        val key = logoKeyForFileName(name) ?: continue
                        if (!mime.startsWith("image/") || size > LOGO_MAX_BYTES) continue
                        into[key] = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                    }
                }
        } catch (_: Exception) {
            // A revoked permission or flaky provider must never crash rendering.
        }
    }

    private fun readTreeUri(context: Context): String? = prefs(context).getString(KEY_TREE_URI, null)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun zipDir(context: Context) = File(context.filesDir, "logo_pack")
}
