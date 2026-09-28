package app.clearsms.mms

import app.clearsms.sms.SimInfo
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The data-SIM judgement behind the `onDataSim` log field and the
 * "MMS may only work on the mobile-data SIM" hint. Synthetic subscriptions
 * only: 3 in slot 1, 7 in slot 2.
 */
class DataSimTest {
    private val slot1 = SimInfo(subscriptionId = 3, slotIndex = 0, displayName = "Work")
    private val slot2 = SimInfo(subscriptionId = 7, slotIndex = 1, displayName = "Personal")
    private val dual = listOf(slot1, slot2)

    // region sendsOnDataSim (the log field)

    @Test
    fun `sending on the data subscription is YES, on the other one NO`() {
        assertThat(DataSim.sendsOnDataSim(sendingSubscriptionId = 3, dataSubscriptionId = 3)).isTrue()
        assertThat(DataSim.sendsOnDataSim(sendingSubscriptionId = 7, dataSubscriptionId = 3)).isFalse()
    }

    @Test
    fun `an unknown side - system-default sender or unknown data SIM - is UNKNOWN, never a guess`() {
        assertThat(DataSim.sendsOnDataSim(sendingSubscriptionId = null, dataSubscriptionId = 3)).isNull()
        assertThat(DataSim.sendsOnDataSim(sendingSubscriptionId = 7, dataSubscriptionId = null)).isNull()
        assertThat(DataSim.sendsOnDataSim(sendingSubscriptionId = null, dataSubscriptionId = null)).isNull()
    }

    // endregion

    // region hintFor (the user-facing guidance)

    @Test
    fun `a no-MMS-network failure sent from the non-data SIM on a dual-SIM phone gets the hint with both slots`() {
        val hint = DataSim.hintFor(SendFailureReason.NO_MMS_NETWORK, sendingSubscriptionId = 7, dataSubscriptionId = 3, activeSims = dual)

        assertThat(hint).isEqualTo(DataSimHint(sendingSlot = 2, dataSlot = 1))
    }

    @Test
    fun `an APN failure gets the hint too - the platform resolves MMS settings per subscription`() {
        val hint =
            DataSim.hintFor(
                SendFailureReason.APN_CONFIGURATION,
                sendingSubscriptionId = 3,
                dataSubscriptionId = 7,
                activeSims = dual,
            )

        assertThat(hint).isEqualTo(DataSimHint(sendingSlot = 1, dataSlot = 2))
    }

    @Test
    fun `only the two bearer-shaped reasons carry the hint - everything else would be noise`() {
        val hinted = SendFailureReason.entries.filter { DataSim.hintFor(it, 7, 3, dual) != null }

        assertThat(hinted).containsExactly(SendFailureReason.NO_MMS_NETWORK, SendFailureReason.APN_CONFIGURATION)
        assertThat(DataSim.HINTED_REASONS).containsExactlyElementsIn(hinted)
        // HTTP_FAILURE in particular: the MMSC answered, so the bearer was up.
        assertThat(DataSim.hintFor(SendFailureReason.HTTP_FAILURE, 7, 3, dual)).isNull()
        assertThat(DataSim.hintFor(null, 7, 3, dual)).isNull()
    }

    @Test
    fun `matching SIMs say nothing extra`() {
        assertThat(DataSim.hintFor(SendFailureReason.NO_MMS_NETWORK, sendingSubscriptionId = 3, dataSubscriptionId = 3, activeSims = dual))
            .isNull()
    }

    @Test
    fun `a single-SIM phone never gets the hint, even when the ids differ`() {
        // A stale row from a removed second SIM: the data SIM is the only SIM left.
        assertThat(
            DataSim.hintFor(
                SendFailureReason.NO_MMS_NETWORK,
                sendingSubscriptionId = 7,
                dataSubscriptionId = 3,
                activeSims = listOf(slot1),
            ),
        ).isNull()
        assertThat(
            DataSim.hintFor(SendFailureReason.NO_MMS_NETWORK, sendingSubscriptionId = 7, dataSubscriptionId = 3, activeSims = emptyList()),
        ).isNull()
    }

    @Test
    fun `an unknown data SIM or a system-default sender never gets the hint`() {
        assertThat(
            DataSim.hintFor(SendFailureReason.NO_MMS_NETWORK, sendingSubscriptionId = 7, dataSubscriptionId = null, activeSims = dual),
        ).isNull()
        assertThat(
            DataSim.hintFor(SendFailureReason.NO_MMS_NETWORK, sendingSubscriptionId = null, dataSubscriptionId = 3, activeSims = dual),
        ).isNull()
    }

    @Test
    fun `a sending or data subscription that is no longer active cannot be placed in a slot - no hint`() {
        val other = SimInfo(subscriptionId = 11, slotIndex = 1, displayName = "Third")
        // Sending SIM 7 was swapped for 11; the data SIM is still 3.
        assertThat(DataSim.hintFor(SendFailureReason.NO_MMS_NETWORK, 7, 3, listOf(slot1, other))).isNull()
        // Data default points at a subscription that is not active.
        assertThat(DataSim.hintFor(SendFailureReason.NO_MMS_NETWORK, 7, 99, dual)).isNull()
    }

    @Test
    fun `the hint carries slot numbers only - no name, id or number`() {
        val hint = DataSim.hintFor(SendFailureReason.NO_MMS_NETWORK, 7, 3, dual)!!

        assertWithMessage("a hint is two slot numbers, nothing else")
            // Compose's stability inference adds a synthetic `$stable`; only the declared members count.
            .that(
                DataSimHint::class.java.declaredFields
                    .filterNot { it.isSynthetic || it.name.startsWith("$") }
                    .map { it.name },
            ).containsExactly("sendingSlot", "dataSlot")
        assertThat(hint.toString()).doesNotContain("Personal")
        assertThat(hint.toString()).doesNotContain("Work")
    }

    // endregion
}
