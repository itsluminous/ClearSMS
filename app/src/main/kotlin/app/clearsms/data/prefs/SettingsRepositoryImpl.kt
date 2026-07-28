package app.clearsms.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.FinanceTab
import app.clearsms.domain.model.NotificationAction
import app.clearsms.domain.model.OtpAutoDeletePolicy
import app.clearsms.domain.model.OtpDisplaySize
import app.clearsms.domain.model.StartDestination
import app.clearsms.domain.model.SummaryFrequency
import app.clearsms.domain.model.SwipeAction
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
        // fromStored migrates legacy values ("DEFAULT", lettered options) in
        // place; nothing is rewritten until the user picks a new option.
        dataStore.data.map { OtpDisplaySize.fromStored(it[KEY_OTP_DISPLAY_SIZE]) }

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

    override val showBalance: Flow<Boolean> =
        dataStore.data.map { it[KEY_SHOW_BALANCE] ?: true }

    override suspend fun setShowBalance(value: Boolean) {
        dataStore.edit { it[KEY_SHOW_BALANCE] = value }
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

    override val showRichAvatars: Flow<Boolean> =
        dataStore.data.map { it[KEY_SHOW_RICH_AVATARS] ?: true }

    override suspend fun setShowRichAvatars(value: Boolean) {
        dataStore.edit { it[KEY_SHOW_RICH_AVATARS] = value }
    }

    override val notificationActions: Flow<Set<NotificationAction>> =
        dataStore.data.map { prefs ->
            prefs[KEY_NOTIFICATION_ACTIONS]?.let { stored ->
                stored
                    .mapNotNull { name ->
                        NotificationAction.entries.firstOrNull { it.name == name }
                    }.toSet()
            } ?: DEFAULT_NOTIFICATION_ACTIONS
        }

    override suspend fun setNotificationActions(value: Set<NotificationAction>) {
        dataStore.edit { prefs ->
            prefs[KEY_NOTIFICATION_ACTIONS] = value.map { it.name }.toSet()
        }
    }

    override val swipeActionStart: Flow<SwipeAction> =
        dataStore.data.map { it[KEY_SWIPE_ACTION_START].toEnum(SwipeAction.ARCHIVE) }

    override suspend fun setSwipeActionStart(value: SwipeAction) {
        dataStore.edit { it[KEY_SWIPE_ACTION_START] = value.name }
    }

    override val swipeActionEnd: Flow<SwipeAction> =
        dataStore.data.map { it[KEY_SWIPE_ACTION_END].toEnum(SwipeAction.DELETE) }

    override suspend fun setSwipeActionEnd(value: SwipeAction) {
        dataStore.edit { it[KEY_SWIPE_ACTION_END] = value.name }
    }

    override val defaultDestination: Flow<StartDestination> =
        dataStore.data.map { it[KEY_DEFAULT_DESTINATION].toEnum(StartDestination.INBOX) }

    override suspend fun setDefaultDestination(value: StartDestination) {
        dataStore.edit { it[KEY_DEFAULT_DESTINATION] = value.name }
    }

    override val defaultInboxFilter: Flow<Category?> =
        dataStore.data.map { prefs ->
            when (val stored = prefs[KEY_DEFAULT_INBOX_FILTER]) {
                null -> Category.IMPORTANT
                FILTER_ALL -> null
                else -> stored.toEnum(Category.IMPORTANT)
            }
        }

    override suspend fun setDefaultInboxFilter(value: Category?) {
        dataStore.edit { it[KEY_DEFAULT_INBOX_FILTER] = value?.name ?: FILTER_ALL }
    }

    override val financeTab: Flow<FinanceTab> =
        dataStore.data.map { it[KEY_FINANCE_TAB].toEnum(FinanceTab.ACCOUNTS) }

    override suspend fun setFinanceTab(value: FinanceTab) {
        dataStore.edit { it[KEY_FINANCE_TAB] = value.name }
    }

    override val transactionNotifications: Flow<Boolean> =
        dataStore.data.map { it[KEY_TRANSACTION_NOTIFICATIONS] ?: true }

    override suspend fun setTransactionNotifications(value: Boolean) {
        dataStore.edit { it[KEY_TRANSACTION_NOTIFICATIONS] = value }
    }

    override val handledOtpMessageId: Flow<Long> =
        dataStore.data.map { it[KEY_HANDLED_OTP_MESSAGE_ID] ?: 0L }

    override suspend fun setHandledOtpMessageId(value: Long) {
        dataStore.edit { it[KEY_HANDLED_OTP_MESSAGE_ID] = value }
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
        val KEY_SHOW_BALANCE = booleanPreferencesKey("show_balance")
        val KEY_SIGNATURE = stringPreferencesKey("signature")
        val KEY_ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val KEY_SHOW_RICH_AVATARS = booleanPreferencesKey("show_rich_avatars")
        val KEY_NOTIFICATION_ACTIONS = stringSetPreferencesKey("notification_actions")
        val KEY_SWIPE_ACTION_START = stringPreferencesKey("swipe_action_start")
        val KEY_SWIPE_ACTION_END = stringPreferencesKey("swipe_action_end")
        val KEY_DEFAULT_DESTINATION = stringPreferencesKey("default_destination")
        val KEY_DEFAULT_INBOX_FILTER = stringPreferencesKey("default_inbox_filter")
        val KEY_FINANCE_TAB = stringPreferencesKey("finance_tab")
        val KEY_TRANSACTION_NOTIFICATIONS = booleanPreferencesKey("transaction_notifications")
        val KEY_HANDLED_OTP_MESSAGE_ID = longPreferencesKey("handled_otp_message_id")

        /** Sentinel stored when the default inbox filter is All (null). */
        const val FILTER_ALL = "ALL"

        val DEFAULT_NOTIFICATION_ACTIONS =
            setOf(NotificationAction.MARK_READ, NotificationAction.REPLY)
    }
}
