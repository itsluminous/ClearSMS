package app.clearsms.mms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.service.carrier.CarrierMessagingService
import android.telephony.SmsManager
import androidx.core.content.FileProvider
import app.clearsms.BuildConfig
import app.clearsms.diagnostics.Diag
import app.clearsms.diagnostics.DiagField.Companion.count
import app.clearsms.diagnostics.DiagField.Companion.flag
import app.clearsms.diagnostics.DiagField.Companion.id
import app.clearsms.diagnostics.DiagField.Companion.packageName
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
     * Hands the staged `m-send-req` PDU in [pduFile] for Room row
     * [messageId] to the platform MMS service on the manager for
     * [subscriptionId] (null = system default manager). The outcome lands
     * in [sentIntent]. Takes the FILE (not a content URI) so URI
     * construction - a framework concern - stays inside the framework
     * implementation. [messageId] is a diagnostics correlation key only.
     *
     * @throws StagedPduUnreadableException when the file cannot be opened
     *   through this app's own FileProvider - the platform could not read
     *   it either, so nothing is handed over.
     */
    fun sendMultimediaMessage(
        messageId: Long,
        subscriptionId: Int?,
        pduFile: File,
        sentIntent: PendingIntent,
    )
}

/**
 * Production [MmsGateway]: grants the packages that will read the staged
 * PDU access to it, verifies we can read it ourselves the way the platform
 * will, and delegates to the framework [SmsManager].
 *
 * DIAGNOSTICS (issue #51): one `mms pdu grant` line per package the grant
 * was attempted for (resolved by the platform or a fallback name) saying
 * whether it landed, then one `mms pdu staged` line with the file's
 * existence and byte length, whether OUR read of the FileProvider URI
 * succeeded and how many bytes it saw, the carrier's `maxMessageSize`
 * and whether the PDU fits under it (the platform's own pre-network size
 * check). The PDU is sized to fit upstream ([MmsSizeBudget]) - no config
 * override travels: one mechanism decides the limit. Package names are
 * software identifiers, never personal; the file NAME is never logged.
 * See [PduReadGrant] and [StagedPduCheck] for the decisions.
 *
 * PRIVACY NOTE: like MMS retrieval, submission is a transaction the
 * Android system's MMS service performs with the carrier's MMSC over the
 * carrier's own MMS APN - this app itself holds no INTERNET permission.
 * The PDU is never copied anywhere: the platform reads the app-private
 * staging file through a per-URI grant only.
 */
@Singleton
class FrameworkMmsGateway
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val carrierLimits: CarrierMmsLimits,
    ) : MmsGateway {
        override fun sendMultimediaMessage(
            messageId: Long,
            subscriptionId: Int?,
            pduFile: File,
            sentIntent: PendingIntent,
        ) {
            val exists = pduFile.exists()
            val lengthBytes = if (exists) pduFile.length() else 0L
            val contentUri =
                try {
                    FileProvider.getUriForFile(context, AUTHORITY, pduFile)
                } catch (e: IllegalArgumentException) {
                    // The file lies outside the provider's configured roots:
                    // a path mismatch on OUR side. The platform could never
                    // have read it; say so and stop here.
                    logStaged(
                        messageId,
                        exists,
                        lengthBytes,
                        readableBytes = null,
                        carrierMax = null,
                        grants = emptyList(),
                    )
                    throw StagedPduUnreadableException(e)
                }
            val grants = PduReadGrant(::resolvePduReaders) { pkg -> grantRead(pkg, contentUri) }.grantAll()
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
            val manager = smsManagerFor(context, subscriptionId)
            val readableBytes = readableLength(contentUri)
            val carrierMax = carrierLimits.maxMessageSizeBytes(subscriptionId)
            logStaged(messageId, exists, lengthBytes, readableBytes, carrierMax, grants)
            if (readableBytes == null || readableBytes <= 0L) {
                // We cannot read our own staged file through the provider:
                // the platform certainly cannot either. Fail before it does.
                throw StagedPduUnreadableException()
            }
            manager.sendMultimediaMessage(context, contentUri, null, null, sentIntent)
        }

        /**
         * The staged file as the platform is about to see it: on disk, and
         * through the provider. `readable=false` with `exists=true` is a
         * provider fault on our side; `exists=false` a lost file.
         * `fitsCarrierMax` is the platform's own size check, applied here
         * in advance (false with a known limit should never happen - the
         * sender refuses such a PDU before this point).
         */
        private fun logStaged(
            messageId: Long,
            exists: Boolean,
            lengthBytes: Long,
            readableBytes: Long?,
            carrierMax: Int?,
            grants: List<PduReadGrant.Outcome>,
        ) {
            Diag.i(
                TAG,
                "mms pdu staged",
                id("message", messageId),
                flag("exists", exists),
                count("bytes", lengthBytes),
                flag("readable", readableBytes != null),
                count("readableBytes", readableBytes ?: 0L),
                count("carrierMaxBytes", carrierMax ?: 0),
                flag("fitsCarrierMax", MmsSizeBudget.forCarrier(carrierMax).fits(lengthBytes)),
                count("grantsAttempted", grants.size),
                count("grantsLanded", grants.count { it.granted }),
            )
        }

        /**
         * Who will read the PDU on THIS device, asked of the platform rather
         * than assumed: every package offering the documented
         * [CarrierMessagingService] (the carrier/OEM override point the
         * framework hands the PDU to first) and every package sharing the
         * telephony uid (where the AOSP MMS service lives). Requires the
         * `<queries>` declaration in the manifest on Android 11+.
         */
        private fun resolvePduReaders(): List<String> {
            val pm = context.packageManager
            val carrierServices =
                runCatching {
                    pm
                        .queryIntentServices(Intent(CarrierMessagingService.SERVICE_INTERFACE), PackageManager.MATCH_ALL)
                        .mapNotNull { it.serviceInfo?.packageName }
                }.getOrDefault(emptyList())
            val telephony = runCatching { pm.getPackagesForUid(Process.PHONE_UID)?.toList() }.getOrNull().orEmpty()
            return carrierServices + telephony
        }

        private fun grantRead(
            pkg: String,
            uri: Uri,
        ) = context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

        /**
         * Opens the URI exactly as the platform MMS service will
         * (`ContentResolver.openFileDescriptor(uri, "r")`) and returns the
         * byte length it sees, or null when the provider refuses - a path
         * mapping or provider fault on our side.
         */
        private fun readableLength(uri: Uri): Long? =
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
            } catch (_: Exception) {
                null
            }

        private companion object {
            const val TAG = "MmsGateway"
            const val AUTHORITY = BuildConfig.APPLICATION_ID + ".fileprovider"
        }
    }
