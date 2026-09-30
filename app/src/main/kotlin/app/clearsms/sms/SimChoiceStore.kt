package app.clearsms.sms

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import app.clearsms.data.repository.PhoneNumberKey
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
        /**
         * The remembered subscription id for [recipient], or null if never
         * chosen. A choice stored under the pre-#42 ten-digit key (a Polish
         * or German number whose key moved with the region-aware
         * normalizer) is still found, and is carried over to the current
         * key on the way out so the legacy entry stops mattering.
         */
        suspend fun rememberedFor(recipient: String): Int? {
            val prefs = dataStore.data.first()
            prefs[keyFor(recipient)]?.let { return it }
            val legacyKey = legacyKeyFor(recipient) ?: return null
            val legacy = prefs[legacyKey] ?: return null
            dataStore.edit {
                it[keyFor(recipient)] = legacy
                it.remove(legacyKey)
            }
            return legacy
        }

        /** Persists [subscriptionId] as the SIM for [recipient]. */
        suspend fun remember(
            recipient: String,
            subscriptionId: Int,
        ) {
            dataStore.edit {
                it[keyFor(recipient)] = subscriptionId
                legacyKeyFor(recipient)?.let(it::remove)
            }
        }

        private fun keyFor(recipient: String): Preferences.Key<Int> = intPreferencesKey(KEY_PREFIX + SenderNormalizer.normalize(recipient))

        /** The pre-#42 key for a phone number, when it differs from the current one. */
        private fun legacyKeyFor(recipient: String): Preferences.Key<Int>? {
            if (!SenderNormalizer.isPhoneNumber(recipient)) return null
            val legacy = PhoneNumberKey.legacyKey(recipient)
            if (legacy.isEmpty() || legacy == SenderNormalizer.normalize(recipient)) return null
            return intPreferencesKey(KEY_PREFIX + legacy)
        }

        private companion object {
            const val KEY_PREFIX = "sim_choice_"
        }
    }
