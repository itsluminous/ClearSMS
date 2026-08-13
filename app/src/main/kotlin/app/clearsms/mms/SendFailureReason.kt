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
 */
enum class SendFailureReason {
    /** The MMS data connection never came up (data off, or carrier MMS dead). */
    NO_MMS_NETWORK,

    /** Carrier MMS settings (APN) missing or rejected. */
    APN_CONFIGURATION,

    /** The MMSC answered with an HTTP-level failure. */
    HTTP_FAILURE,

    /** Radio/IO trouble worth retrying. */
    TRANSIENT,

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
                SmsManager.MMS_ERROR_RETRY,
                SmsManager.MMS_ERROR_IO_ERROR,
                -> TRANSIENT
                else -> UNKNOWN
            }
    }
}
