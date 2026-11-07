package app.clearsms.receiver

import android.content.Intent
import android.provider.Telephony
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A malformed `SMS_DELIVER` broadcast (missing, null or garbage PDUs) must
 * decode to zero parts - never throw - because [SmsReceiver.onReceive] runs
 * the decode directly and a throw there crashes the process on every
 * redelivery of the same message.
 */
@RunWith(RobolectricTestRunner::class)
class SmsReceiverMalformedIntentTest {
    @Test
    fun `intent without pdus extra decodes to no parts`() {
        val intent = Intent(Telephony.Sms.Intents.SMS_DELIVER_ACTION)
        assertThat(SmsReceiver.extractParts(intent)).isEmpty()
    }

    @Test
    fun `intent with empty pdu array decodes to no parts`() {
        val intent =
            Intent(Telephony.Sms.Intents.SMS_DELIVER_ACTION)
                .putExtra("pdus", arrayOf<ByteArray>())
                .putExtra("format", "3gpp")
        assertThat(SmsReceiver.extractParts(intent)).isEmpty()
    }

    @Test
    fun `pdu count is read as a count, never decoding the bytes, and is -1 without the extra`() {
        // The "deliver received" trace line is emitted before any decoding,
        // so its PDU count must come from the array's size alone.
        assertThat(SmsReceiver.pduCount(Intent(Telephony.Sms.Intents.SMS_DELIVER_ACTION))).isEqualTo(-1)
        assertThat(
            SmsReceiver.pduCount(Intent(Telephony.Sms.Intents.SMS_DELIVER_ACTION).putExtra("pdus", arrayOf<ByteArray>())),
        ).isEqualTo(0)
        assertThat(
            SmsReceiver.pduCount(
                Intent(Telephony.Sms.Intents.SMS_DELIVER_ACTION)
                    .putExtra("pdus", arrayOf(byteArrayOf(0x00, 0x01), byteArrayOf(0x02))),
            ),
        ).isEqualTo(2)
        // A non-array extra is a malformed broadcast: -1, not a throw.
        assertThat(SmsReceiver.pduCount(Intent(Telephony.Sms.Intents.SMS_DELIVER_ACTION).putExtra("pdus", "junk"))).isEqualTo(-1)
    }

    @Test
    fun `intent with garbage pdu bytes decodes to no parts`() {
        val intent =
            Intent(Telephony.Sms.Intents.SMS_DELIVER_ACTION)
                .putExtra("pdus", arrayOf(byteArrayOf(0x00, 0x01, 0x02)))
                .putExtra("format", "3gpp")
        assertThat(SmsReceiver.extractParts(intent)).isEmpty()
    }
}
