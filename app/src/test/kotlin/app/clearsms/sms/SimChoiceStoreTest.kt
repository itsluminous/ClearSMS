package app.clearsms.sms

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.io.File

/** Per-recipient SIM memory: set, recall, and number-form equivalence. */
class SimChoiceStoreTest {
    private lateinit var store: SimChoiceStore

    @Before
    fun setUp() {
        store =
            SimChoiceStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("sim_choice", ".preferences_pb")
                },
            )
    }

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

    @Test
    fun `re-remembering overwrites the previous choice`() =
        runBlocking {
            store.remember("+919812345678", 7)
            store.remember("+919812345678", 3)

            assertThat(store.rememberedFor("+919812345678")).isEqualTo(3)
        }
}
