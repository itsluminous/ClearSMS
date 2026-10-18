package app.clearsms.sms

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import app.clearsms.data.repository.PhoneNumberKey
import app.clearsms.data.repository.SenderNormalizer
import app.clearsms.di.UiSettingsDataStore
import app.clearsms.diagnostics.Diag
import kotlinx.coroutines.CancellationException
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
                it -= legacyKey
            }
            return legacy
        }

        /**
         * Persists [subscriptionId] as the SIM for [recipient], and drops any
         * entry still sitting under the pre-#42 key so the legacy lookup in
         * [rememberedFor] can never resurrect a stale choice.
         *
         * The removal MUST stay a `-=` (Unit-returning). `MutablePreferences
         * .remove` returns its erased generic `T`, which is null when the key
         * is absent - the normal case: a fresh install, or a number whose
         * legacy entry was already migrated. Written as `?.let(it::remove)`
         * the compiler unboxes that null as an `Int` and the DataStore write
         * dies with an NPE, which took the whole app down on the first
         * SIM-icon tap for every non-ten-digit number (issue #111; 0.22 and
         * 0.23). Pinned by `SimChoiceStoreTest`.
         */
        suspend fun remember(
            recipient: String,
            subscriptionId: Int,
        ) {
            dataStore.edit {
                it[keyFor(recipient)] = subscriptionId
                val legacyKey = legacyKeyFor(recipient)
                if (legacyKey != null) it -= legacyKey
            }
        }

        /**
         * [remember] for the fire-and-forget launch behind the SIM-icon tap.
         * By the time it runs the switch has already happened in memory, so
         * failing to persist it (a bad DataStore file, a full disk, a bug
         * like #111) costs only the memory for next time - never the app. The
         * failure is recorded for the diagnostic report; cancellation still
         * propagates, as it must inside a coroutine.
         */
        suspend fun rememberOrLog(
            recipient: String,
            subscriptionId: Int,
        ) {
            try {
                remember(recipient, subscriptionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diag.w(TAG, "sim choice persist failed", e)
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
            const val TAG = "SimChoiceStore"
            const val KEY_PREFIX = "sim_choice_"
        }
    }
