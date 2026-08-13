package app.clearsms.testing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [DataStore] for `Preferences`-based tests (UiPrefs and friends).
 *
 * Use this instead of `PreferenceDataStoreFactory.create` with a temp file:
 * the real file-backed DataStore runs its actor on `Dispatchers.IO`, outside
 * the test scheduler, and datastore 1.1.x has an upstream race
 * (b/431787506, fixed only in 1.3.0-alpha03) where a NEW collector on
 * `DataStore.data` that starts concurrently with an `updateData` call can
 * miss the new value and never emit it - which hung
 * `RulesViewModelDetailTest` on 2-vCPU CI runners (UncompletedCoroutinesError
 * after 60s, reproduced and thread-dumped in a `--cpus=2` Linux container).
 * A [MutableStateFlow] always serves the latest value to late collectors, so
 * the race cannot exist and no real IO is left to starve.
 */
class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val next = transform(state.value).toPreferences()
        state.value = next
        return next
    }
}
