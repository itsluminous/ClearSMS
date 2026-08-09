package app.clearsms.receiver

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Which SIM an incoming SMS is attributed to, from the broadcast extras. */
class SmsReceiverSubscriptionTest {
    @Test
    fun `reads the historical subscription extra first`() {
        val extras = mapOf("subscription" to 7, "android.telephony.extra.SUBSCRIPTION_INDEX" to 3)

        assertThat(SmsReceiver.extractSubscriptionId { key, def -> extras[key] ?: def }).isEqualTo(7)
    }

    @Test
    fun `falls back to the documented SUBSCRIPTION_INDEX extra`() {
        val extras = mapOf("android.telephony.extra.SUBSCRIPTION_INDEX" to 3)

        assertThat(SmsReceiver.extractSubscriptionId { key, def -> extras[key] ?: def }).isEqualTo(3)
    }

    @Test
    fun `no extra at all yields null - single-SIM broadcasts stay untagged`() {
        assertThat(SmsReceiver.extractSubscriptionId { _, def -> def }).isNull()
    }

    @Test
    fun `negative (invalid) subscription values are treated as absent`() {
        val extras = mapOf("subscription" to -1, "android.telephony.extra.SUBSCRIPTION_INDEX" to -1)

        assertThat(SmsReceiver.extractSubscriptionId { key, def -> extras[key] ?: def }).isNull()
    }
}
