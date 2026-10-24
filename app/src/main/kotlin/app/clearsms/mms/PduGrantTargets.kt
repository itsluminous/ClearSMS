package app.clearsms.mms

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.service.carrier.CarrierMessagingService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The ONE answer to "which packages will touch a staged PDU on this
 * device?", shared by the send path (which grants a READ on the
 * `m-send-req` it staged) and the download path (which grants a WRITE on
 * the slot the retrieved `m-retrieve-conf` lands in).
 *
 * Asked of the platform rather than assumed: every package offering the
 * documented [CarrierMessagingService] - the carrier/OEM override point the
 * framework hands the PDU to first, which is where a carrier such as
 * Verizon ships its own messaging service - and every package sharing the
 * telephony uid, where the AOSP MMS service lives. Requires the `<queries>`
 * declaration in the manifest on Android 11+. A platform refusal reads as
 * "nobody resolved"; [PduGrant] then falls back to the two AOSP names.
 *
 * Issue #135: the send path resolved its readers this way since issue #51
 * while the download path kept a hardcoded pair, so a device whose MMS
 * service lived elsewhere could send but never download. Both paths now
 * call this and only this; `MmsDiagnosticsContractTest` pins it.
 */
@Singleton
class PduGrantTargets
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /** Resolved package names, carrier services first; never throws. */
        fun resolve(): List<String> {
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
    }
