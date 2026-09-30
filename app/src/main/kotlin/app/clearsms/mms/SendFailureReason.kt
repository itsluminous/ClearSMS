package app.clearsms.mms

import android.telephony.SmsManager

/**
 * Human-explainable cause of a failed outgoing send, persisted on the
 * message row so the Retry dialog can say more than "Not sent".
 *
 * MMS codes come from the platform's send result ([SmsManager]'s
 * MMS_ERROR_* constants). The distinction that matters most in practice:
 * [NO_MMS_NETWORK] - the carrier's MMS bearer never came up. On several
 * networks (notably in India, where carriers have wound MMS down) NO app
 * can send MMS on such a SIM; the message should say so instead of
 * inviting endless retries.
 *
 * The RAW result code is still what a bug report needs (several codes
 * share one reason here), so the send-report receiver logs both the code
 * and the reason - see `MmsSentReceiver`.
 */
enum class SendFailureReason {
    /** The MMS data connection never came up (data off, or carrier MMS dead). */
    NO_MMS_NETWORK,

    /** Carrier MMS settings (APN) missing or rejected. */
    APN_CONFIGURATION,

    /** The MMSC answered with an HTTP-level failure. */
    HTTP_FAILURE,

    /** The carrier app asked for a retry over its own network - genuinely transient. */
    TRANSIENT,

    /**
     * The platform's MMS service could not READ the staged PDU and gave up
     * before touching the network (`MMS_ERROR_IO_ERROR` on a send arises
     * in AOSP only from `readPduFromContentUri` returning null: no read
     * grant for the reading uid, an empty file, or a PDU larger than the
     * carrier config `maxMessageSize`). It returns within milliseconds and
     * a bare retry repeats it exactly - so, unlike [TRANSIENT], it must
     * not invite one (issue #51).
     */
    PDU_REJECTED,

    /**
     * The encoded PDU is larger than the sending SIM's carrier
     * `maxMessageSize`, so the platform would have refused to read it
     * (see [PDU_REJECTED]) - this app checked first and never handed it
     * over. Attachments are compressed to fit that limit when staged; this
     * remains possible when the SIM is switched to a stricter carrier after
     * attaching, or an extreme body outgrows the envelope margin.
     */
    EXCEEDS_CARRIER_LIMIT,

    /** The platform says the carrier has MMS switched off for this SIM. */
    CARRIER_DISABLED,

    /** The subscription the message was sent from is gone or inactive. */
    SIM_UNAVAILABLE,

    /**
     * The message never reached the platform's MMS service: encoding,
     * staging or the hand-over itself threw before a result code could
     * exist. This is the "our side" failure, as opposed to every other
     * reason, which the platform or carrier reported.
     */
    DISPATCH_FAILED,

    /** Anything else. */
    UNKNOWN,

    ;

    companion object {
        /** Maps the platform's MMS send [resultCode] to a reason. */
        fun fromMmsResultCode(resultCode: Int): SendFailureReason =
            when (resultCode) {
                SmsManager.MMS_ERROR_NO_DATA_NETWORK,
                SmsManager.MMS_ERROR_DATA_DISABLED,
                SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS,
                -> NO_MMS_NETWORK
                SmsManager.MMS_ERROR_INVALID_APN,
                SmsManager.MMS_ERROR_CONFIGURATION_ERROR,
                -> APN_CONFIGURATION
                SmsManager.MMS_ERROR_HTTP_FAILURE -> HTTP_FAILURE
                SmsManager.MMS_ERROR_RETRY -> TRANSIENT
                SmsManager.MMS_ERROR_IO_ERROR -> PDU_REJECTED
                SmsManager.MMS_ERROR_MMS_DISABLED_BY_CARRIER -> CARRIER_DISABLED
                SmsManager.MMS_ERROR_INVALID_SUBSCRIPTION_ID,
                SmsManager.MMS_ERROR_INACTIVE_SUBSCRIPTION,
                -> SIM_UNAVAILABLE
                else -> UNKNOWN
            }

        /** The persisted name back to a reason; null for null or an unknown name. */
        fun fromName(name: String?): SendFailureReason? = entries.firstOrNull { it.name == name }
    }
}
