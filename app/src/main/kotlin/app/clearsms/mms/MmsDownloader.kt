package app.clearsms.mms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.FileProvider
import app.clearsms.BuildConfig
import app.clearsms.diagnostics.Diag
import app.clearsms.diagnostics.DiagField.Companion.count
import app.clearsms.diagnostics.DiagField.Companion.flag
import app.clearsms.diagnostics.DiagField.Companion.id
import app.clearsms.diagnostics.DiagField.Companion.packageName
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
 * GRANT (issue #135): the platform MMS service runs outside this app's
 * sandbox and must WRITE the retrieved PDU into our staging URI. Which
 * package that is varies by carrier and OEM - Verizon ships its own
 * carrier messaging service on many devices - so the targets are resolved
 * through [PduGrantTargets], the SAME resolver the send path uses, with
 * the two AOSP names kept as fallbacks ([PduGrant]). The download path
 * once hardcoded that pair alone and every retrieve on such a device was
 * refused the write.
 *
 * DIAGNOSTICS: one `mms pdu grant` line per package the grant was
 * attempted for (resolved or fallback) saying whether it landed; the
 * counts and the granted names ride the result PendingIntent so the
 * receiver's failure line can say what the grant reached. Package names
 * are software identifiers, never personal.
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
        private val grantTargets: PduGrantTargets,
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
                val grants = PduGrant(grantTargets::resolve) { pkg -> grantWrite(pkg, uri) }.grantAll()
                grants.forEach { outcome ->
                    Diag.i(
                        TAG,
                        "mms pdu grant",
                        id("message", messageId),
                        packageName(outcome.packageName),
                        flag("resolved", outcome.resolved),
                        flag("granted", outcome.granted),
                    )
                }
                Diag.i(
                    TAG,
                    "mms download started",
                    id("message", messageId),
                    count("attempt", attempt),
                    count("grantsAttempted", grants.size),
                    count("grantsLanded", grants.count { it.granted }),
                )
                smsManager().downloadMultimediaMessage(
                    context,
                    contentLocation,
                    uri,
                    null,
                    resultIntent(messageId, attempt, grants),
                )
            } catch (e: Exception) {
                // A failure to even start the transaction is a download
                // failure: the receiver never fires, so mark it here by
                // broadcasting the failure path ourselves.
                Diag.e(TAG, "mms download start failed", e, id("message", messageId), count("attempt", attempt))
                context.sendBroadcast(
                    MmsDownloadReceiver.intent(context, messageId, attempt).putExtra(MmsDownloadReceiver.EXTRA_START_FAILED, true),
                )
            }
        }

        /** Read AND write: the platform writes the retrieved PDU into the staging slot. */
        private fun grantWrite(
            pkg: String,
            uri: Uri,
        ) = context.grantUriPermission(
            pkg,
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )

        /**
         * The result target, carrying what the grant reached so the failure
         * line can say so. `FLAG_UPDATE_CURRENT` replaces the extras on a
         * retry: each attempt's result reports its own grants.
         */
        private fun resultIntent(
            messageId: Long,
            attempt: Int,
            grants: List<PduGrant.Outcome>,
        ): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                // Unique per message so parallel downloads never collide.
                messageId.toInt(),
                MmsDownloadReceiver.intent(context, messageId, attempt).withGrants(grants),
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
            const val TAG = "MmsDownload"
            const val AUTHORITY = BuildConfig.APPLICATION_ID + ".fileprovider"

            fun Intent.withGrants(grants: List<PduGrant.Outcome>): Intent =
                putExtra(MmsDownloadReceiver.EXTRA_GRANTS_ATTEMPTED, grants.size)
                    .putExtra(MmsDownloadReceiver.EXTRA_GRANTS_LANDED, grants.count { it.granted })
                    .putExtra(
                        MmsDownloadReceiver.EXTRA_GRANTED_PACKAGES,
                        grants.filter { it.granted }.map { it.packageName }.toTypedArray(),
                    )
        }
    }
