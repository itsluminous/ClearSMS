package app.clearsms.di

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The ingest gates (blocked senders/keywords, recycle bin) must NEVER be
 * able to hang an incoming message. The old wiring opened a cold DataStore
 * collector per message, which races concurrent settings writes (upstream
 * b/431787506: a collector attaching during updateData may never emit) -
 * found live as a silently dropped SMS arriving right after an unblock.
 * [DataModule.hotGateForTest] reads a warm shared cache instead and falls
 * back to the initial value rather than suspending indefinitely.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HotGateTest {
    @Test
    fun `reads the current value after emission`() =
        runTest {
            val source = MutableStateFlow(setOf("DOMINO"))
            val gate = DataModule.hotGateForTest(backgroundScope, source, emptySet())
            assertThat(gate()).containsExactly("DOMINO")
        }

    @Test
    fun `sees later writes through the same shared collector`() =
        runTest {
            val source = MutableStateFlow(setOf("DOMINO"))
            val gate = DataModule.hotGateForTest(backgroundScope, source, emptySet())
            gate()
            source.value = emptySet()
            // The shared collector delivers on the scheduler - one tick, like
            // the real DataStore delivery. Eventual visibility is fine; the
            // bug being pinned is HANGING, never staleness-by-a-tick.
            testScheduler.runCurrent()
            assertThat(gate()).isEmpty()
        }

    @Test
    fun `a source that never emits cannot hang ingest - falls back to initial`() =
        runTest {
            val never: Flow<Set<String>> =
                flow {
                    CompletableDeferred<Unit>().await() // suspends forever, emits nothing
                }
            val gate = DataModule.hotGateForTest(backgroundScope, never, setOf("FALLBACK"))
            // Must return (virtual time skips the 2s cap) instead of suspending.
            assertThat(gate()).containsExactly("FALLBACK")
        }

    @Test
    fun `many concurrent reads share one collector`() =
        runTest {
            var collections = 0
            val source =
                flow {
                    collections++
                    emit(setOf("X"))
                    CompletableDeferred<Unit>().await() // stay open like DataStore
                }
            val gate = DataModule.hotGateForTest(backgroundScope, source, emptySet<String>())
            repeat(50) { assertThat(gate()).containsExactly("X") }
            assertThat(collections).isEqualTo(1)
        }
}
