package app.clearsms.data.prefs

import app.clearsms.domain.model.OtpAutoDeletePolicy
import app.clearsms.domain.model.OtpDisplaySize
import app.clearsms.domain.model.SummaryFrequency
import app.clearsms.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/** User settings backed by Preferences DataStore. */
interface SettingsRepository {
    val theme: Flow<ThemeMode>

    suspend fun setTheme(value: ThemeMode)

    val otpAutoCopy: Flow<Boolean>

    suspend fun setOtpAutoCopy(value: Boolean)

    val otpAutoDeletePolicy: Flow<OtpAutoDeletePolicy>

    suspend fun setOtpAutoDeletePolicy(value: OtpAutoDeletePolicy)

    val otpDisplaySize: Flow<OtpDisplaySize>

    suspend fun setOtpDisplaySize(value: OtpDisplaySize)

    val summaryFrequency: Flow<SummaryFrequency>

    suspend fun setSummaryFrequency(value: SummaryFrequency)

    val showTransactionDetails: Flow<Boolean>

    suspend fun setShowTransactionDetails(value: Boolean)

    val signature: Flow<String>

    suspend fun setSignature(value: String)

    val onboardingComplete: Flow<Boolean>

    suspend fun setOnboardingComplete(value: Boolean)
}
