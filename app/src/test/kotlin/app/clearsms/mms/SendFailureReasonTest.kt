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
    fun `carrier-disabled and inactive-subscription codes get their own reasons`() {
        assertThat(SendFailureReason.fromMmsResultCode(SmsManager.MMS_ERROR_MMS_DISABLED_BY_CARRIER))
            .isEqualTo(SendFailureReason.CARRIER_DISABLED)
        assertThat(SendFailureReason.fromMmsResultCode(SmsManager.MMS_ERROR_INVALID_SUBSCRIPTION_ID))
            .isEqualTo(SendFailureReason.SIM_UNAVAILABLE)
        assertThat(SendFailureReason.fromMmsResultCode(SmsManager.MMS_ERROR_INACTIVE_SUBSCRIPTION))
            .isEqualTo(SendFailureReason.SIM_UNAVAILABLE)
    }

    @Test
    fun `every documented platform code maps to a specific reason - only unlisted codes are UNKNOWN`() {
        // The complete MMS_ERROR_* set of compileSdk 35. A new platform code
        // should be classified here deliberately, not fall through.
        val documented =
            mapOf(
                SmsManager.MMS_ERROR_UNSPECIFIED to SendFailureReason.UNKNOWN,
                SmsManager.MMS_ERROR_INVALID_APN to SendFailureReason.APN_CONFIGURATION,
                SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS to SendFailureReason.NO_MMS_NETWORK,
                SmsManager.MMS_ERROR_HTTP_FAILURE to SendFailureReason.HTTP_FAILURE,
                SmsManager.MMS_ERROR_IO_ERROR to SendFailureReason.PDU_REJECTED,
                SmsManager.MMS_ERROR_RETRY to SendFailureReason.TRANSIENT,
                SmsManager.MMS_ERROR_CONFIGURATION_ERROR to SendFailureReason.APN_CONFIGURATION,
                SmsManager.MMS_ERROR_NO_DATA_NETWORK to SendFailureReason.NO_MMS_NETWORK,
                SmsManager.MMS_ERROR_INVALID_SUBSCRIPTION_ID to SendFailureReason.SIM_UNAVAILABLE,
                SmsManager.MMS_ERROR_INACTIVE_SUBSCRIPTION to SendFailureReason.SIM_UNAVAILABLE,
                SmsManager.MMS_ERROR_DATA_DISABLED to SendFailureReason.NO_MMS_NETWORK,
                SmsManager.MMS_ERROR_MMS_DISABLED_BY_CARRIER to SendFailureReason.CARRIER_DISABLED,
            )
        assertThat(documented.keys).containsExactlyElementsIn(1..12)
        documented.forEach { (code, reason) ->
            assertThat(SendFailureReason.fromMmsResultCode(code)).isEqualTo(reason)
        }
        // Codes the platform never sends today (0 = RESULT_OK is not a
        // failure; negatives and 13+ are undocumented) read as UNKNOWN.
        listOf(0, 13, 99, -1, Int.MAX_VALUE, Int.MIN_VALUE).forEach { code ->
            assertThat(SendFailureReason.fromMmsResultCode(code)).isEqualTo(SendFailureReason.UNKNOWN)
        }
        // DISPATCH_FAILED is never produced from a code: it is the app's own.
        assertThat(documented.values).doesNotContain(SendFailureReason.DISPATCH_FAILED)
    }

    @Test
    fun `sms result codes map only where the platform's meaning is clear`() {
        // Radio off / no service: the phone had no network.
        assertThat(SendFailureReason.fromSmsResultCode(SmsManager.RESULT_ERROR_RADIO_OFF))
            .isEqualTo(SendFailureReason.NO_SERVICE)
        assertThat(SendFailureReason.fromSmsResultCode(SmsManager.RESULT_ERROR_NO_SERVICE))
            .isEqualTo(SendFailureReason.NO_SERVICE)
        // The platform's own premium-short-code guard refused the send: the
        // one SMS failure a short-code reply (GitHub #75) is likely to meet.
        assertThat(SendFailureReason.fromSmsResultCode(SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED))
            .isEqualTo(SendFailureReason.SHORT_CODE_BLOCKED)
        assertThat(SendFailureReason.fromSmsResultCode(SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED))
            .isEqualTo(SendFailureReason.SHORT_CODE_BLOCKED)
        // GENERIC_FAILURE is what an alphanumeric or network-rejected
        // destination produces; its cause lives in a RIL extra this app does
        // not interpret, so it reads as UNKNOWN ("without saying why") - as
        // do the rare codes and anything undocumented.
        listOf(
            SmsManager.RESULT_ERROR_GENERIC_FAILURE,
            SmsManager.RESULT_ERROR_NULL_PDU,
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED,
            SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE,
            0,
            -1,
            999,
        ).forEach { code ->
            assertThat(SendFailureReason.fromSmsResultCode(code)).isEqualTo(SendFailureReason.UNKNOWN)
        }
        // The MMS table and the SMS table never produce the app's own reason.
        assertThat(SendFailureReason.fromSmsResultCode(SmsManager.RESULT_ERROR_GENERIC_FAILURE))
            .isNotEqualTo(SendFailureReason.DISPATCH_FAILED)
    }

    @Test
    fun `persisted names round-trip and junk reads as no reason`() {
        SendFailureReason.entries.forEach { reason ->
            assertThat(SendFailureReason.fromName(reason.name)).isEqualTo(reason)
        }
        assertThat(SendFailureReason.fromName(null)).isNull()
        assertThat(SendFailureReason.fromName("")).isNull()
        assertThat(SendFailureReason.fromName("no_mms_network")).isNull()
        assertThat(SendFailureReason.fromName("SOMETHING_FROM_A_NEWER_BUILD")).isNull()
    }

    @Test
    fun `carrier-app RETRY is TRANSIENT, an IO error is a PDU rejection, and everything else is UNKNOWN`() {
        assertThat(SendFailureReason.fromMmsResultCode(SmsManager.MMS_ERROR_RETRY))
            .isEqualTo(SendFailureReason.TRANSIENT)
        // Issue #51: on a send, AOSP returns MMS_ERROR_IO_ERROR only when it
        // could not read the PDU - before any network attempt. Not transient.
        assertThat(SendFailureReason.fromMmsResultCode(SmsManager.MMS_ERROR_IO_ERROR))
            .isEqualTo(SendFailureReason.PDU_REJECTED)
        assertThat(SendFailureReason.fromMmsResultCode(-42)).isEqualTo(SendFailureReason.UNKNOWN)
        assertThat(SendFailureReason.fromMmsResultCode(SmsManager.MMS_ERROR_UNSPECIFIED))
            .isEqualTo(SendFailureReason.UNKNOWN)
    }
}
