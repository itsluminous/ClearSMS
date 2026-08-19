package app.clearsms.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.FinanceTab
import app.clearsms.domain.model.LogoBackground
import app.clearsms.domain.model.NotificationAction
import app.clearsms.domain.model.OtpAutoDeletePolicy
import app.clearsms.domain.model.OtpDisplaySize
import app.clearsms.domain.model.StartDestination
import app.clearsms.domain.model.SwipeAction
import app.clearsms.domain.model.ThemeMode
import app.clearsms.ui.alerts.AlertFilter
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

    override val showTransactionDetails: Flow<Boolean> =
        dataStore.data.map { it[KEY_SHOW_TRANSACTION_DETAILS] ?: true }

    override suspend fun setShowTransactionDetails(value: Boolean) {
        dataStore.edit { it[KEY_SHOW_TRANSACTION_DETAILS] = value }
    }

    override val recycleBinEnabled: Flow<Boolean> =
        dataStore.data.map { it[KEY_RECYCLE_BIN_ENABLED] ?: true }

    override suspend fun setRecycleBinEnabled(value: Boolean) {
        dataStore.edit { it[KEY_RECYCLE_BIN_ENABLED] = value }
    }

    override val showBalance: Flow<Boolean> =
        dataStore.data.map { it[KEY_SHOW_BALANCE] ?: false }

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

    override val defaultFinanceFilter: Flow<FinanceTab> =
        dataStore.data.map { it[KEY_DEFAULT_FINANCE_FILTER].toEnum(FinanceTab.ACCOUNTS) }

    override suspend fun setDefaultFinanceFilter(value: FinanceTab) {
        dataStore.edit { it[KEY_DEFAULT_FINANCE_FILTER] = value.name }
    }

    override val transactionNotifications: Flow<Boolean> =
        dataStore.data.map { it[KEY_TRANSACTION_NOTIFICATIONS] ?: true }

    override suspend fun setTransactionNotifications(value: Boolean) {
        dataStore.edit { it[KEY_TRANSACTION_NOTIFICATIONS] = value }
    }

    override val logoBackground: Flow<LogoBackground> =
        dataStore.data.map { it[KEY_LOGO_BACKGROUND].toEnum(LogoBackground.NONE) }

    override suspend fun setLogoBackground(value: LogoBackground) {
        dataStore.edit { it[KEY_LOGO_BACKGROUND] = value.name }
    }

    override val scheduleSendTipShown: Flow<Boolean> =
        dataStore.data.map { it[KEY_SCHEDULE_SEND_TIP_SHOWN] ?: false }

    override suspend fun setScheduleSendTipShown(value: Boolean) {
        dataStore.edit { it[KEY_SCHEDULE_SEND_TIP_SHOWN] = value }
    }

    override val handledOtpMessageId: Flow<Long> =
        dataStore.data.map { it[KEY_HANDLED_OTP_MESSAGE_ID] ?: 0L }

    override suspend fun setHandledOtpMessageId(value: Long) {
        dataStore.edit { it[KEY_HANDLED_OTP_MESSAGE_ID] = value }
    }

    override val inboxPillOrder: Flow<List<Category>> =
        dataStore.data.map { it[KEY_INBOX_PILL_ORDER].toEnumOrder() }

    override suspend fun setInboxPillOrder(value: List<Category>) {
        dataStore.edit { it[KEY_INBOX_PILL_ORDER] = value.toStoredOrder() }
    }

    override val financePillOrder: Flow<List<FinanceTab>> =
        dataStore.data.map { it[KEY_FINANCE_PILL_ORDER].toEnumOrder() }

    override suspend fun setFinancePillOrder(value: List<FinanceTab>) {
        dataStore.edit { it[KEY_FINANCE_PILL_ORDER] = value.toStoredOrder() }
    }

    override val blockedKeywords: Flow<Set<String>> =
        dataStore.data.map { it[KEY_BLOCKED_KEYWORDS] ?: emptySet() }

    override suspend fun setBlockedKeywords(value: Set<String>) {
        dataStore.edit { it[KEY_BLOCKED_KEYWORDS] = value }
    }

    override val blockedSenders: Flow<Set<String>> =
        dataStore.data.map { it[KEY_BLOCKED_SENDERS] ?: emptySet() }

    override suspend fun setBlockedSenders(value: Set<String>) {
        dataStore.edit { it[KEY_BLOCKED_SENDERS] = value }
    }

    override val alertsPillOrder: Flow<List<AlertFilter>> =
        dataStore.data.map { it[KEY_ALERTS_PILL_ORDER].toEnumOrder() }

    override suspend fun setAlertsPillOrder(value: List<AlertFilter>) {
        dataStore.edit { it[KEY_ALERTS_PILL_ORDER] = value.toStoredOrder() }
    }

    override val lastSortedVersionCode: Flow<Int> =
        dataStore.data.map { it[KEY_LAST_SORTED_VERSION_CODE] ?: 0 }

    override suspend fun setLastSortedVersionCode(value: Int) {
        dataStore.edit { it[KEY_LAST_SORTED_VERSION_CODE] = value }
    }

    private inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
        this?.let { name ->
            enumValues<T>().firstOrNull { it.name == name }
        } ?: default

    /**
     * Decodes a delimited list of enum names into a complete pill order:
     * unknown names are dropped, duplicates collapse to the first mention,
     * and every enum entry missing from the stored value is appended in
     * declaration order - a future pill can therefore never be hidden by a
     * stale stored order. Null (nothing stored) yields declaration order.
     */
    private inline fun <reified T : Enum<T>> String?.toEnumOrder(): List<T> {
        val stored =
            this
                ?.split(ORDER_DELIMITER)
                ?.mapNotNull { name -> enumValues<T>().firstOrNull { it.name == name } }
                ?.distinct()
                .orEmpty()
        return stored + enumValues<T>().filter { it !in stored }
    }

    private fun List<Enum<*>>.toStoredOrder(): String = joinToString(ORDER_DELIMITER) { it.name }

    private companion object {
        val KEY_THEME = stringPreferencesKey("theme")
        val KEY_OTP_AUTO_COPY = booleanPreferencesKey("otp_auto_copy")
        val KEY_OTP_AUTO_DELETE = stringPreferencesKey("otp_auto_delete_policy")
        val KEY_OTP_DISPLAY_SIZE = stringPreferencesKey("otp_display_size")
        val KEY_SHOW_TRANSACTION_DETAILS = booleanPreferencesKey("show_transaction_details")
        val KEY_RECYCLE_BIN_ENABLED = booleanPreferencesKey("recycle_bin_enabled")
        val KEY_SHOW_BALANCE = booleanPreferencesKey("show_balance")
        val KEY_SIGNATURE = stringPreferencesKey("signature")
        val KEY_ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val KEY_SHOW_RICH_AVATARS = booleanPreferencesKey("show_rich_avatars")
        val KEY_NOTIFICATION_ACTIONS = stringSetPreferencesKey("notification_actions")
        val KEY_SWIPE_ACTION_START = stringPreferencesKey("swipe_action_start")
        val KEY_SWIPE_ACTION_END = stringPreferencesKey("swipe_action_end")
        val KEY_DEFAULT_DESTINATION = stringPreferencesKey("default_destination")
        val KEY_DEFAULT_INBOX_FILTER = stringPreferencesKey("default_inbox_filter")
        val KEY_DEFAULT_FINANCE_FILTER = stringPreferencesKey("default_finance_filter")
        val KEY_TRANSACTION_NOTIFICATIONS = booleanPreferencesKey("transaction_notifications")
        val KEY_LOGO_BACKGROUND = stringPreferencesKey("logo_background")
        val KEY_HANDLED_OTP_MESSAGE_ID = longPreferencesKey("handled_otp_message_id")
        val KEY_SCHEDULE_SEND_TIP_SHOWN = booleanPreferencesKey("schedule_send_tip_shown")
        val KEY_INBOX_PILL_ORDER = stringPreferencesKey("inbox_pill_order")
        val KEY_FINANCE_PILL_ORDER = stringPreferencesKey("finance_pill_order")
        val KEY_ALERTS_PILL_ORDER = stringPreferencesKey("alerts_pill_order")
        val KEY_BLOCKED_KEYWORDS = stringSetPreferencesKey("blocked_keywords")
        val KEY_BLOCKED_SENDERS = stringSetPreferencesKey("blocked_senders")
        val KEY_LAST_SORTED_VERSION_CODE = intPreferencesKey("last_sorted_version_code")

        /** Separator for the stored pill-order enum name lists. */
        const val ORDER_DELIMITER = ","

        /** Sentinel stored when the default inbox filter is All (null). */
        const val FILTER_ALL = "ALL"

        /**
         * Default trio: Mark read + Reply + Delete - exactly fills the
         * platform's 3-action cap with the three most common triage moves
         * (acknowledge, respond, discard). Share exists as an option but is
         * OFF by default: forwarding message text is a deliberate opt-in.
         */
        val DEFAULT_NOTIFICATION_ACTIONS =
            setOf(NotificationAction.MARK_READ, NotificationAction.REPLY, NotificationAction.DELETE)
    }
}
