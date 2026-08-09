package app.clearsms.ui.common

import app.clearsms.testing.FakeSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The one-time "long-press Send to schedule" tip: the first send claims it,
 * every later send declines, the persisted flag is set exactly once, and
 * scheduling consumes it silently.
 */
class ScheduleTipGateTest {
    @Test
    fun `first send shows the tip, second does not`() =
        runBlocking {
            val settings = FakeSettingsRepository()
            val gate = ScheduleTipGate(settings)

            assertThat(gate.shouldShowTip()).isTrue()
            assertThat(gate.shouldShowTip()).isFalse()
            assertThat(settings.scheduleSendTipShown.value).isTrue()
        }

    @Test
    fun `racing sends can never both claim the first slot`() =
        runBlocking {
            val gate = ScheduleTipGate(FakeSettingsRepository())

            val outcomes = (1..8).map { async { gate.shouldShowTip() } }.map { it.await() }

            assertThat(outcomes.count { it }).isEqualTo(1)
        }

    @Test
    fun `an already-set flag never tips`() =
        runBlocking {
            val settings = FakeSettingsRepository()
            settings.setScheduleSendTipShown(true)

            assertThat(ScheduleTipGate(settings).shouldShowTip()).isFalse()
        }

    @Test
    fun `scheduling consumes the tip without showing it`() =
        runBlocking {
            val settings = FakeSettingsRepository()
            val gate = ScheduleTipGate(settings)

            gate.markShown()

            assertThat(settings.scheduleSendTipShown.value).isTrue()
            assertThat(gate.shouldShowTip()).isFalse()
        }
}
