package app.clearsms.mms

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The carrier's MMS size limit for a SIM, as the platform will apply it.
 *
 * In AOSP (`MmsRequest.readPduFromContentUri`) the MMS service refuses to
 * READ a staged `m-send-req` larger than the carrier config
 * `maxMessageSize` and answers `MMS_ERROR_IO_ERROR` at once - before any
 * network activity (issue #51: a 326 369-byte PDU against the 307 200-byte
 * default failed in 22-30 ms, every time). So the limit is not the MMSC's
 * business alone: it decides whether the phone will even try. This seam
 * exists so the compose path can size attachments to it and so tests can
 * pretend to be any carrier.
 */
interface CarrierMmsLimits {
    /**
     * The carrier config `maxMessageSize` (bytes) for [subscriptionId]
     * (null = the system default SIM), or null when the platform will not
     * say (no telephony, a throwing manager, a zero or negative value).
     */
    fun maxMessageSizeBytes(subscriptionId: Int?): Int?
}

/** Reads the limit from the chosen SIM's [SmsManager] carrier config. */
@Singleton
class FrameworkCarrierMmsLimits
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : CarrierMmsLimits {
        override fun maxMessageSizeBytes(subscriptionId: Int?): Int? =
            runCatching { smsManagerFor(context, subscriptionId).carrierConfigValues?.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, 0) }
                .getOrNull()
                ?.takeIf { it > 0 }
    }

/** The [SmsManager] for the chosen SIM, per API level - the same rule the SMS path applies. */
internal fun smsManagerFor(
    context: Context,
    subscriptionId: Int?,
): SmsManager =
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

/**
 * How many bytes an outgoing MMS may carry, derived from the carrier's
 * limit rather than from a fixed budget of our own - so a phone whose
 * carrier config says 300 KiB gets an attachment that FITS (the platform
 * reads it, the MMSC gets to judge it) and a carrier that allows more
 * gets the better image, not a needlessly small one.
 *
 * - [limitBytes] is what the platform will compare the whole PDU against:
 *   the carrier value when known, else [AOSP_DEFAULT_MAX_MESSAGE_SIZE].
 *   The default is the SAFER unknown-limit assumption: when carrier config
 *   has no entry the platform itself falls back to exactly this number, so
 *   a PDU sized for it is never refused pre-network - whereas the old
 *   1 MB budget would be, instantly, on every such SIM (issue #51).
 * - [attachmentTargetBytes] is the compression target for the message's
 *   attachments together: the limit minus [PDU_ENVELOPE_MARGIN_BYTES] for
 *   the `m-send-req` envelope (headers, the text part, per-part headers).
 *   The envelope measured 154 bytes around the reporter's single-image
 *   PDU (326 215 -> 326 369); 8 KiB covers a several-thousand-character
 *   body and a handful of parts with long names, yet costs under 3% of
 *   the AOSP default limit - so the image the carrier allows is nearly all
 *   image. An exceptionally long body that still overflows is caught at
 *   hand-over ([MmsSender]) and refused truthfully, never handed to the
 *   platform to fail at once.
 */
data class MmsSizeBudget(
    /** The carrier config value the budget was derived from; null when unknown. */
    val carrierMaxBytes: Int?,
) {
    /** False when the carrier limit could not be read and the AOSP default stands in. */
    val limitKnown: Boolean get() = carrierMaxBytes != null

    /** The PDU size ceiling the platform will enforce. */
    val limitBytes: Long get() = (carrierMaxBytes ?: AOSP_DEFAULT_MAX_MESSAGE_SIZE).toLong()

    /** Total attachment bytes to aim for, envelope margin already taken. */
    val attachmentTargetBytes: Long get() = (limitBytes - PDU_ENVELOPE_MARGIN_BYTES).coerceAtLeast(MIN_TARGET_BYTES)

    /** Whether a PDU of [pduBytes] will get past the platform's own size check. */
    fun fits(pduBytes: Long): Boolean = pduBytes in 1..limitBytes

    companion object {
        /** AOSP `MmsConfig` default `maxMessageSize`: 300 KiB. */
        const val AOSP_DEFAULT_MAX_MESSAGE_SIZE = 307_200

        /** Headroom for the `m-send-req` envelope around the attachments. */
        const val PDU_ENVELOPE_MARGIN_BYTES = 8_192L

        /** A nonsensically small carrier value never drives the target to zero. */
        private const val MIN_TARGET_BYTES = 1_024L

        /** The budget for a carrier reporting [carrierMaxBytes] (null, zero or negative = unknown). */
        fun forCarrier(carrierMaxBytes: Int?): MmsSizeBudget = MmsSizeBudget(carrierMaxBytes?.takeIf { it > 0 })
    }
}
