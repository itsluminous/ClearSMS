package app.clearsms.shortcuts

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.IconCompat
import app.clearsms.BuildConfig
import app.clearsms.diagnostics.Diag
import app.clearsms.diagnostics.DiagField.Companion.count
import app.clearsms.notification.NotificationSender
import app.clearsms.notification.SenderIconFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The conversation-shortcut avatar as a **content-URI icon**.
 *
 * A conversation shortcut is long-lived (`setLongLived(true)`, required for
 * Android 11 conversation notifications), and the system CACHES long-lived
 * shortcuts - so it refuses to cache a raw bitmap icon: `ShortcutService`
 * logs `Invalid icon type in shortcut ... Bitmaps are not allowed in
 * long-lived shortcuts` for every publish today, and AOSP carries a TODO to
 * throw instead, which would reject the whole list - every launcher
 * shortcut and every Direct Share target - on a future Android. The
 * documented route is an icon the system REFERENCES rather than copies:
 * [IconCompat.createWithAdaptiveBitmapContentUri]. The system stores the
 * URI string, and when the launcher or the share sheet needs the picture
 * it asks the system, which issues that process a read grant on that one
 * URI on this app's behalf (`ShortcutService.getShortcutIconUri`) and the
 * launcher reads it through the provider. Below API 30 the compat layer
 * decodes the file in this process and submits a bitmap, which is correct
 * there (no caching, no long-lived shortcuts before 30).
 *
 * **What is exposed, and how it is scoped.** The URI is served by the app's
 * EXISTING `FileProvider` (`<applicationId>.fileprovider`, the one MMS
 * attachments and diagnostic reports already use): `exported="false"`,
 * `grantUriPermissions="true"`, roots fixed in `res/xml/mms_file_paths.xml`.
 * This class adds exactly one root, `filesDir/shortcuts/icons/`, and writes
 * exactly one kind of file into it: the rendered avatar square
 * ([SenderIconFactory.shortcutAvatarFor]) as a PNG named by app thread id.
 * Nothing else is placed there; the provider refuses any path outside its
 * roots, so no other app-private file - the database, the attachment
 * store, the logs, settings - becomes reachable. No component is exported
 * and no permission is added: a reader needs a per-URI grant, which only
 * the system issues, only for a URI it was handed as a shortcut icon, only
 * to the launcher or chooser asking for that shortcut. The app still makes
 * no network call and holds no INTERNET permission; the file never leaves
 * the device.
 *
 * **What the file contains.** The same pixels the system was previously
 * handed in-process and persisted into its own `/data/system_ce` bitmap
 * cache: a letter tile, a brand mark, or a 216 px copy of a contact photo
 * on a plate. Names, bodies, numbers never. The avatar is byte-identical to
 * the inbox-matching derivation ([SenderIconFactory.shortcutAvatarFor] is
 * the single source; PNG is lossless), so the launcher renders exactly what
 * it rendered before.
 *
 * **Lifecycle.** One file per thread, replaced in place on every publish
 * that renders it (an atomic rename, so a reader never sees a half-written
 * file). [retainOnly] drops the files of threads no longer published or
 * pinned; [deleteAll] empties the root when the user turns the setting
 * off, so nothing avatar-shaped survives the switch. Uninstall removes
 * the app's private storage, and with it every file here.
 *
 * **Failure.** If the file cannot be written (storage full, provider
 * misconfigured), the publish does not fail: the bitmap icon is submitted
 * as before, which the system tolerates today, and the fallback is logged.
 */
@Singleton
class ConversationShortcutIcons
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val iconFactory: SenderIconFactory,
    ) {
        private val root: File get() = File(context.filesDir, RELATIVE_DIR)

        /**
         * The icon for [threadId]'s shortcut, rendered for [sender]: a
         * content-URI adaptive icon over the avatar file this writes, or the
         * in-memory adaptive bitmap when the file could not be written.
         */
        fun iconFor(
            threadId: Long,
            sender: NotificationSender,
        ): IconCompat {
            val avatar = iconFactory.shortcutAvatarFor(sender)
            return try {
                val file = fileFor(threadId)
                writeAtomically(avatar, file)
                IconCompat.createWithAdaptiveBitmapContentUri(FileProvider.getUriForFile(context, AUTHORITY, file))
            } catch (e: IOException) {
                Diag.w(TAG, "shortcut avatar file not written; publishing a bitmap icon instead", e)
                IconCompat.createWithAdaptiveBitmap(avatar)
            } catch (e: IllegalArgumentException) {
                // FileProvider: no configured root contains the file.
                Diag.w(TAG, "shortcut avatar not servable; publishing a bitmap icon instead", e)
                IconCompat.createWithAdaptiveBitmap(avatar)
            }
        }

        /** Deletes every avatar file except those of [threadIds] - the threads still published or pinned. */
        fun retainOnly(threadIds: Set<Long>) {
            val keep = threadIds.mapTo(HashSet()) { fileFor(it).name }
            val stale = root.listFiles()?.filter { it.name !in keep }.orEmpty()
            if (stale.isEmpty()) return
            stale.forEach { it.delete() }
            Diag.d(TAG, "stale shortcut avatar files deleted", count("count", stale.size))
        }

        /** Deletes every avatar file: the setting is off, nothing avatar-shaped may remain. */
        fun deleteAll() {
            val files = root.listFiles().orEmpty()
            if (files.isEmpty()) return
            files.forEach { it.delete() }
            Diag.d(TAG, "all shortcut avatar files deleted", count("count", files.size))
        }

        /** The avatar file for [threadId]; internal so tests can read the bytes the launcher will. */
        internal fun fileFor(threadId: Long): File = File(root, "thread-$threadId.png")

        /** Writes [bitmap] as PNG to a sibling temp file, then renames over [target]. */
        private fun writeAtomically(
            bitmap: Bitmap,
            target: File,
        ) {
            val dir = target.parentFile ?: throw IOException("no parent directory")
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create avatar directory")
            val temp = File(dir, target.name + ".tmp")
            FileOutputStream(temp).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("png encode failed")
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                throw IOException("rename failed")
            }
        }

        companion object {
            private const val TAG = "ConversationShortcuts"

            /** The provider root for shortcut avatars, relative to `filesDir`; pinned against `mms_file_paths.xml` by test. */
            internal const val RELATIVE_DIR = "shortcuts/icons"

            /** The app's single FileProvider authority. */
            internal const val AUTHORITY = BuildConfig.APPLICATION_ID + ".fileprovider"
        }
    }
