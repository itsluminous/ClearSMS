package app.clearsms.sms

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

/** Per-recipient SIM memory: set, recall, and number-form equivalence. */
class SimChoiceStoreTest {
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: SimChoiceStore

    @Before
    fun setUp() {
        dataStore =
            PreferenceDataStoreFactory.create {
                File.createTempFile("sim_choice", ".preferences_pb")
            }
        store = SimChoiceStore(dataStore)
    }

    /** The pre-#42 key: the number's last ten digits, as 0.21 wrote it. */
    private val legacyPolishKey = intPreferencesKey("sim_choice_8601234567")

    @Test
    fun `remembers a choice per recipient and recalls it`() =
        runBlocking {
            assertThat(store.rememberedFor("+919812345678")).isNull()

            store.remember("+919812345678", 7)

            assertThat(store.rememberedFor("+919812345678")).isEqualTo(7)
        }

    @Test
    fun `choices are independent between recipients`() =
        runBlocking {
            store.remember("+919812345678", 7)
            store.remember("+919899999999", 3)

            assertThat(store.rememberedFor("+919812345678")).isEqualTo(7)
            assertThat(store.rememberedFor("+919899999999")).isEqualTo(3)
        }

    @Test
    fun `different spellings of one number share one remembered choice`() =
        runBlocking {
            // Same number with and without the country prefix normalizes to
            // one key - the choice follows the person, not the spelling.
            store.remember("+919812345678", 7)

            assertThat(store.rememberedFor("919812345678")).isEqualTo(7)
        }

    /**
     * Issue #111: a number whose region-aware key differs from the pre-#42
     * ten-digit key (a Polish nine-digit number here; its `+48` form used
     * to be keyed by its last ten digits, `8601234567`) has a legacy key
     * to clean up on every write - but on a fresh install there is no legacy
     * entry, and `MutablePreferences.remove` of an absent key returns null
     * through its erased generic. Unboxing that null crashed the app with
     * an NPE on the first SIM-icon tap in 0.22 and 0.23.
     */
    @Test
    fun `remembering for a number with a legacy key but no legacy entry does not throw`() =
        runBlocking {
            store.remember("+48601234567", 2)

            assertThat(store.rememberedFor("+48601234567")).isEqualTo(2)
        }

    @Test
    fun `remembering migrates and removes a stale legacy entry`() =
        runBlocking {
            // Simulate a choice written by 0.21 under the ten-digit key.
            dataStore.edit { it[legacyPolishKey] = 5 }
            assertThat(store.rememberedFor("+48601234567")).isEqualTo(5)

            store.remember("+48601234567", 2)

            assertThat(store.rememberedFor("+48601234567")).isEqualTo(2)
            assertThat(dataStore.data.first()[legacyPolishKey]).isNull()
        }

    /**
     * The fire-and-forget launch behind the SIM-icon tap goes through
     * [SimChoiceStore.rememberOrLog]: a store that cannot be written costs
     * the memory, never the app.
     */
    @Test
    fun `rememberOrLog swallows a failing store instead of throwing`() =
        runBlocking {
            val broken =
                object : DataStore<Preferences> {
                    override val data: Flow<Preferences> = flow { throw IOException("disk full") }

                    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                        throw IOException("disk full")
                }

            SimChoiceStore(broken).rememberOrLog("+48601234567", 2)
        }

    @Test
    fun `re-remembering overwrites the previous choice`() =
        runBlocking {
            store.remember("+919812345678", 7)
            store.remember("+919812345678", 3)

            assertThat(store.rememberedFor("+919812345678")).isEqualTo(3)
        }
}
