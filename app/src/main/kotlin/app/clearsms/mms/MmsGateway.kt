package app.clearsms.mms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.FileProvider
import app.clearsms.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin seam over [SmsManager.sendMultimediaMessage] so [MmsSender] is
 * unit-testable without the radio - the exact counterpart of the SMS
 * path's SmsGateway. Implementations must be pure delegation.
 */
interface MmsGateway {
    /**
     * Hands the staged `m-send-req` PDU in [pduFile] to the platform MMS
     * service on the manager for [subscriptionId] (null = system default
     * manager). The outcome lands in [sentIntent]. Takes the FILE (not a
     * content URI) so URI construction - a framework concern - stays
     * inside the framework implementation.
     */
    fun sendMultimediaMessage(
        subscriptionId: Int?,
        pduFile: File,
        sentIntent: PendingIntent,
    )
}

/**
 * Production [MmsGateway]: grants the platform MMS service read access to
 * the staged PDU and delegates to the framework [SmsManager].
 *
 * PRIVACY NOTE: like MMS retrieval, submission is a transaction the
 * Android system's MMS service performs with the carrier's MMSC over the
 * carrier's own MMS APN - this app itself holds no INTERNET permission.
 */
@Singleton
class FrameworkMmsGateway
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : MmsGateway {
        override fun sendMultimediaMessage(
            subscriptionId: Int?,
            pduFile: File,
            sentIntent: PendingIntent,
        ) {
            val contentUri = FileProvider.getUriForFile(context, AUTHORITY, pduFile)
            grantReadToPlatformMmsService(contentUri)
            smsManagerFor(subscriptionId).sendMultimediaMessage(
                context,
                contentUri,
                null,
                null,
                sentIntent,
            )
        }

        /**
         * The platform MMS service runs outside this app's sandbox and
         * must be able to READ the staged PDU. Package names vary by
         * platform version/OEM, so both known homes of the AOSP MMS
         * service get the grant; a missing package is a no-op. (Mirrors
         * the write grant SystemMmsDownloader makes for retrieval.)
         */
        private fun grantReadToPlatformMmsService(uri: Uri) {
            for (pkg in listOf("com.android.phone", "com.android.mms.service")) {
                try {
                    context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                    // Package not present on this device: ignore.
                }
            }
        }

        /** The [SmsManager] for the chosen SIM, per API level - as the SMS path does. */
        private fun smsManagerFor(subscriptionId: Int?): SmsManager =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val default = requireNotNull(context.getSystemService(SmsManager::class.java))
                if (subscriptionId != null) default.createForSubscriptionId(subscriptionId) else default
            } else {
                @Suppress("DEPRECATION")
                if (subscriptionId != null) {
                    SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
                } else {
                    SmsManager.getDefault()
                }
            }

        private companion object {
            const val AUTHORITY = BuildConfig.APPLICATION_ID + ".fileprovider"
        }
    }
