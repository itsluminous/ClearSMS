package app.clearsms.sms

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import app.clearsms.data.repository.SenderNormalizer
import app.clearsms.di.UiSettingsDataStore
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers the user's SIM choice PER RECIPIENT NUMBER.
 *
 * DataStore over a Room table because this is pure key→value data (one int
 * per normalized number) with no relational reads - point lookups on open
 * and a write on tap - so a table, DAO, entity and schema-version bump would
 * buy nothing. Keys are namespaced dynamic preferences in the existing
 * ui_settings store; numbers are normalized with the same
 * [SenderNormalizer] threads use, so "+919812..." and "98 12..." share one
 * remembered choice.
 */
@Singleton
class SimChoiceStore
    @Inject
    constructor(
        @UiSettingsDataStore private val dataStore: DataStore<Preferences>,
    ) {
        /** The remembered subscription id for [recipient], or null if never chosen. */
        suspend fun rememberedFor(recipient: String): Int? = dataStore.data.first()[keyFor(recipient)]

        /** Persists [subscriptionId] as the SIM for [recipient]. */
        suspend fun remember(
            recipient: String,
            subscriptionId: Int,
        ) {
            dataStore.edit { it[keyFor(recipient)] = subscriptionId }
        }

        private fun keyFor(recipient: String): Preferences.Key<Int> = intPreferencesKey(KEY_PREFIX + SenderNormalizer.normalize(recipient))

        private companion object {
            const val KEY_PREFIX = "sim_choice_"
        }
    }
