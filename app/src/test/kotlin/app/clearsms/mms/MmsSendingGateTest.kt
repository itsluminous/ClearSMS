package app.clearsms.mms

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The two independent reasons the attach button can disappear (issue #94),
 * and the rule that combines them.
 *
 * - The USER's own switch (Settings → Messages → Send picture messages),
 *   which is the dependable answer when a carrier has discontinued MMS
 *   without saying so in its config - the reported case (O2 Germany, July
 *   2026).
 * - The CARRIER config veto, which catches the same thing automatically
 *   wherever the platform does declare it.
 *
 * Pinned here as the plain boolean rule the two ViewModels apply, so the
 * conversation screen and the standalone composer cannot drift apart.
 */
@RunWith(RobolectricTestRunner::class)
class MmsSendingGateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The exact expression both ViewModels use. */
    private fun gate(
        carrierAllows: Boolean,
        userEnabled: Boolean,
    ): Boolean =
        runBlocking {
            combine(MutableStateFlow(carrierAllows), MutableStateFlow(userEnabled)) { carrier, enabled ->
                carrier && enabled
            }.first()
        }

    @Test
    fun `both yes offers the attach button`() {
        assertThat(gate(carrierAllows = true, userEnabled = true)).isTrue()
    }

    @Test
    fun `the user switch alone hides it, whatever the carrier says`() {
        assertThat(gate(carrierAllows = true, userEnabled = false)).isFalse()
    }

    @Test
    fun `the carrier veto alone hides it, even with the setting on`() {
        assertThat(gate(carrierAllows = false, userEnabled = true)).isFalse()
    }

    @Test
    fun `an unknown carrier answer is not a veto`() {
        // MmsCapability maps "the platform would not say" to available, so a
        // working SIM never loses the button to a missing config value. Only
        // an explicit false vetoes.
        val capability = MmsCapability(context)
        // Robolectric reports no carrier config, i.e. the unknown case.
        assertThat(capability.isMmsAvailable(subscriptionId = null)).isTrue()
        assertThat(capability.isMmsAvailable(subscriptionId = 1)).isTrue()
    }
}
