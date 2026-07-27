package app.clearsms.work

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncCheckpointStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun store(): SyncCheckpointStore =
        SyncCheckpointStore(
            PreferenceDataStoreFactory.create(scope = scope) {
                tmp.newFile("checkpoint.preferences_pb")
            },
        )

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `defaults to the beginning`() =
        runBlocking {
            val checkpoint = store().get()
            assertThat(checkpoint.lastSystemSmsId).isEqualTo(0L)
            assertThat(checkpoint.processedCount).isEqualTo(0)
        }

    @Test
    fun `set then get round trips`() =
        runBlocking {
            val store = store()
            store.set(SyncCheckpointStore.Checkpoint(lastSystemSmsId = 421L, processedCount = 350))
            val checkpoint = store.get()
            assertThat(checkpoint.lastSystemSmsId).isEqualTo(421L)
            assertThat(checkpoint.processedCount).isEqualTo(350)
        }

    @Test
    fun `clear resets to the beginning`() =
        runBlocking {
            val store = store()
            store.set(SyncCheckpointStore.Checkpoint(lastSystemSmsId = 99L, processedCount = 99))
            store.clear()
            assertThat(store.get()).isEqualTo(SyncCheckpointStore.Checkpoint(0L, 0))
        }
}
