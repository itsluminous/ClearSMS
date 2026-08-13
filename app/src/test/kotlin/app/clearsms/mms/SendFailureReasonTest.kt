package app.clearsms.mms

import android.telephony.SmsManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Maps the platform's MMS send result codes to explainable reasons. The
 * case that matters most: a bearer that never comes up (observed live as a
 * 120s network-request timeout on a carrier that has wound MMS down) must
 * read as NO_MMS_NETWORK, not as generic retryable trouble.
 */
class SendFailureReasonTest {
    @Test
    fun `no-network family maps to NO_MMS_NETWORK`() {
        assertThat(SendFailureReason.fromMmsResultCode(SmsManager.MMS_ERROR_NO_DATA_NETWORK))
            .isEqualTo(SendFailureReason.NO_MMS_NETWORK)
        assertThat(SendFailureReason.fromMmsResultCode(SmsManager.MMS_ERROR_DATA_DISABLED))
            .isEqualTo(SendFailureReason.NO_MMS_NETWORK)
        assertThat(SendFailureReason.fromMmsResultCode(SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS))
            .isEqualTo(SendFailureReason.NO_MMS_NETWORK)
    }

    @Test
    fun `configuration and http map to their own reasons`() {
        assertThat(SendFailureReason.fromMmsResultCode(SmsManager.MMS_ERROR_INVALID_APN))
            .isEqualTo(SendFailureReason.APN_CONFIGURATION)
        assertThat(SendFailureReason.fromMmsResultCode(SmsManager.MMS_ERROR_CONFIGURATION_ERROR))
            .isEqualTo(SendFailureReason.APN_CONFIGURATION)
        assertThat(SendFailureReason.fromMmsResultCode(SmsManager.MMS_ERROR_HTTP_FAILURE))
            .isEqualTo(SendFailureReason.HTTP_FAILURE)
    }

    @Test
    fun `retryable io maps to TRANSIENT and everything else to UNKNOWN`() {
        assertThat(SendFailureReason.fromMmsResultCode(SmsManager.MMS_ERROR_RETRY))
            .isEqualTo(SendFailureReason.TRANSIENT)
        assertThat(SendFailureReason.fromMmsResultCode(SmsManager.MMS_ERROR_IO_ERROR))
            .isEqualTo(SendFailureReason.TRANSIENT)
        assertThat(SendFailureReason.fromMmsResultCode(-42)).isEqualTo(SendFailureReason.UNKNOWN)
        assertThat(SendFailureReason.fromMmsResultCode(SmsManager.MMS_ERROR_UNSPECIFIED))
            .isEqualTo(SendFailureReason.UNKNOWN)
    }
}
