package app.clearsms.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.clearsms.domain.model.OtpAutoDeletePolicy
import app.clearsms.domain.model.OtpDisplaySize
import app.clearsms.domain.model.SummaryFrequency
import app.clearsms.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Default [SettingsRepository] over a Preferences [DataStore]. */
class SettingsRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {
    override val theme: Flow<ThemeMode> =
        dataStore.data.map { it[KEY_THEME].toEnum(ThemeMode.SYSTEM) }

    override suspend fun setTheme(value: ThemeMode) {
        dataStore.edit { it[KEY_THEME] = value.name }
    }

    override val otpAutoCopy: Flow<Boolean> =
        dataStore.data.map { it[KEY_OTP_AUTO_COPY] ?: true }

    override suspend fun setOtpAutoCopy(value: Boolean) {
        dataStore.edit { it[KEY_OTP_AUTO_COPY] = value }
    }

    override val otpAutoDeletePolicy: Flow<OtpAutoDeletePolicy> =
        dataStore.data.map { it[KEY_OTP_AUTO_DELETE].toEnum(OtpAutoDeletePolicy.NEVER) }

    override suspend fun setOtpAutoDeletePolicy(value: OtpAutoDeletePolicy) {
        dataStore.edit { it[KEY_OTP_AUTO_DELETE] = value.name }
    }

    override val otpDisplaySize: Flow<OtpDisplaySize> =
        dataStore.data.map { it[KEY_OTP_DISPLAY_SIZE].toEnum(OtpDisplaySize.DEFAULT) }

    override suspend fun setOtpDisplaySize(value: OtpDisplaySize) {
        dataStore.edit { it[KEY_OTP_DISPLAY_SIZE] = value.name }
    }

    override val summaryFrequency: Flow<SummaryFrequency> =
        dataStore.data.map { it[KEY_SUMMARY_FREQUENCY].toEnum(SummaryFrequency.OFF) }

    override suspend fun setSummaryFrequency(value: SummaryFrequency) {
        dataStore.edit { it[KEY_SUMMARY_FREQUENCY] = value.name }
    }

    override val showTransactionDetails: Flow<Boolean> =
        dataStore.data.map { it[KEY_SHOW_TRANSACTION_DETAILS] ?: true }

    override suspend fun setShowTransactionDetails(value: Boolean) {
        dataStore.edit { it[KEY_SHOW_TRANSACTION_DETAILS] = value }
    }

    override val signature: Flow<String> =
        dataStore.data.map { it[KEY_SIGNATURE].orEmpty() }

    override suspend fun setSignature(value: String) {
        dataStore.edit { it[KEY_SIGNATURE] = value }
    }

    override val onboardingComplete: Flow<Boolean> =
        dataStore.data.map { it[KEY_ONBOARDING_COMPLETE] ?: false }

    override suspend fun setOnboardingComplete(value: Boolean) {
        dataStore.edit { it[KEY_ONBOARDING_COMPLETE] = value }
    }

    private inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
        this?.let { name ->
            enumValues<T>().firstOrNull { it.name == name }
        } ?: default

    private companion object {
        val KEY_THEME = stringPreferencesKey("theme")
        val KEY_OTP_AUTO_COPY = booleanPreferencesKey("otp_auto_copy")
        val KEY_OTP_AUTO_DELETE = stringPreferencesKey("otp_auto_delete_policy")
        val KEY_OTP_DISPLAY_SIZE = stringPreferencesKey("otp_display_size")
        val KEY_SUMMARY_FREQUENCY = stringPreferencesKey("summary_frequency")
        val KEY_SHOW_TRANSACTION_DETAILS = booleanPreferencesKey("show_transaction_details")
        val KEY_SIGNATURE = stringPreferencesKey("signature")
        val KEY_ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
    }
}
