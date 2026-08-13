package app.clearsms.mms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.FileProvider
import app.clearsms.BuildConfig
import app.clearsms.receiver.MmsDownloadReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seam over [SmsManager.downloadMultimediaMessage] so the retrieve flow is
 * testable (and fakeable on an emulator without an MMSC).
 */
interface MmsDownloader {
    /**
     * Starts the platform MMS download for [messageId]'s content at
     * [contentLocation], staging the PDU at
     * [AttachmentStore.stagingFile]; the result lands in
     * [app.clearsms.receiver.MmsDownloadReceiver] tagged with [attempt].
     */
    fun download(
        messageId: Long,
        contentLocation: String,
        attempt: Int,
    )
}

/**
 * Production downloader: hands the transaction to the platform's MMS
 * service via [SmsManager.downloadMultimediaMessage].
 *
 * PRIVACY NOTE: this is the app's single deliberate use of the network, and
 * the HTTP transaction is performed by the Android system's MMS service
 * over the carrier's own MMS APN - retrieving an MMS is how the protocol
 * works, the request never leaves the carrier network, and this app itself
 * holds no INTERNET permission.
 */
@Singleton
class SystemMmsDownloader
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val attachmentStore: AttachmentStore,
    ) : MmsDownloader {
        override fun download(
            messageId: Long,
            contentLocation: String,
            attempt: Int,
        ) {
            try {
                val file = attachmentStore.stagingFile(messageId)
                // Pre-create so the provider can always open it for write.
                if (!file.exists()) file.createNewFile()
                val uri = FileProvider.getUriForFile(context, AUTHORITY, file)
                grantWriteToPlatformMmsService(uri)
                smsManager().downloadMultimediaMessage(
                    context,
                    contentLocation,
                    uri,
                    null,
                    resultIntent(messageId, attempt),
                )
            } catch (e: Exception) {
                // A failure to even start the transaction is a download
                // failure: the receiver never fires, so mark it here by
                // broadcasting the failure path ourselves.
                Log.e(TAG, "Failed to start MMS download", e)
                context.sendBroadcast(
                    MmsDownloadReceiver.intent(context, messageId, attempt).putExtra(MmsDownloadReceiver.EXTRA_START_FAILED, true),
                )
            }
        }

        /**
         * The platform MMS service runs outside this app's sandbox and must
         * be able to write the retrieved PDU into the staging URI. Package
         * names vary by platform version/OEM, so both known homes of the
         * AOSP MMS service get the grant; a missing package is a no-op.
         */
        private fun grantWriteToPlatformMmsService(uri: Uri) {
            for (pkg in listOf("com.android.phone", "com.android.mms.service")) {
                try {
                    context.grantUriPermission(
                        pkg,
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                } catch (_: Exception) {
                    // Package not present on this device: ignore.
                }
            }
        }

        private fun resultIntent(
            messageId: Long,
            attempt: Int,
        ): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                // Unique per message so parallel downloads never collide.
                messageId.toInt(),
                MmsDownloadReceiver.intent(context, messageId, attempt),
                // MUTABLE: the platform fills in the result code.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )

        private fun smsManager(): SmsManager =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

        private companion object {
            const val TAG = "SystemMmsDownloader"
            const val AUTHORITY = BuildConfig.APPLICATION_ID + ".fileprovider"
        }
    }
