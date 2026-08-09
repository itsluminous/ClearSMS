package app.clearsms.work

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seam over SAF tree-document access so [BackupWorker] can be unit-tested
 * without a real documents provider. One store targets one granted backup
 * tree; [openForWrite] returns null when the tree is gone or the persisted
 * permission was revoked - the worker's failure signal.
 */
interface BackupDocumentStore {
    /**
     * Opens the named child document for writing, creating it if absent and
     * truncating any previous content (stable filenames: every run overwrites
     * the last backup). Returns null when the directory no longer exists or
     * access was revoked.
     */
    fun openForWrite(fileName: String): OutputStream?

    interface Factory {
        fun create(treeUri: Uri): BackupDocumentStore
    }
}

/**
 * Production [BackupDocumentStore] over [DocumentsContract] (framework API -
 * no androidx.documentfile dependency): looks the child up by display name
 * among the tree's children, creates it when missing, and opens it in "wt"
 * (write-truncate) mode so a shorter new backup can never leave trailing
 * bytes of the previous one.
 */
class SafBackupDocumentStore(
    private val context: Context,
    private val treeUri: Uri,
) : BackupDocumentStore {
    override fun openForWrite(fileName: String): OutputStream? =
        try {
            val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
            val treeDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)
            val documentUri =
                findChildByName(treeDocumentUri, fileName)
                    ?: DocumentsContract.createDocument(
                        context.contentResolver,
                        treeDocumentUri,
                        "application/json",
                        fileName,
                    )
            documentUri?.let { context.contentResolver.openOutputStream(it, "wt") }
        } catch (e: SecurityException) {
            Log.w(TAG, "Backup directory permission revoked", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Backup directory unavailable", e)
            null
        }

    private fun findChildByName(
        treeDocumentUri: Uri,
        fileName: String,
    ): Uri? {
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getDocumentId(treeDocumentUri),
            )
        context.contentResolver
            .query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == fileName) {
                        return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0))
                    }
                }
            }
        return null
    }

    @Singleton
    class Factory
        @Inject
        constructor(
            @ApplicationContext private val context: Context,
        ) : BackupDocumentStore.Factory {
            override fun create(treeUri: Uri): BackupDocumentStore = SafBackupDocumentStore(context, treeUri)
        }

    private companion object {
        const val TAG = "SafBackupDocumentStore"
    }
}
