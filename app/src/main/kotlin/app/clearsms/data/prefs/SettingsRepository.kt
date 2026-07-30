package app.clearsms.data.prefs

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

    /** Show the parsed extraction card under tapped messages in a conversation. */
    val showTransactionDetails: Flow<Boolean>

    suspend fun setShowTransactionDetails(value: Boolean)

    /**
     * Privacy gate for the Finance tab: when false, balances (account
     * balances, card outstanding, the month summary) are masked until the
     * user authenticates with the device screen lock. Default FALSE —
     * privacy-first: balances stay hidden until the user deliberately
     * reveals them (users who never touched the toggle get the hidden
     * default too). Display-only — nothing sensitive is
     * stored because of this flag.
     */
    val showBalance: Flow<Boolean>

    suspend fun setShowBalance(value: Boolean)

    val signature: Flow<String>

    suspend fun setSignature(value: String)

    val onboardingComplete: Flow<Boolean>

    suspend fun setOnboardingComplete(value: Boolean)

    /** Show contact photos and sender brand marks instead of plain icon/monogram avatars. */
    val showRichAvatars: Flow<Boolean>

    suspend fun setShowRichAvatars(value: Boolean)

    /** Action buttons attached to message notifications (Android shows at most 3). */
    val notificationActions: Flow<Set<NotificationAction>>

    suspend fun setNotificationActions(value: Set<NotificationAction>)

    /** Action for a left-to-right (start) swipe on an inbox row. */
    val swipeActionStart: Flow<SwipeAction>

    suspend fun setSwipeActionStart(value: SwipeAction)

    /** Action for a right-to-left (end) swipe on an inbox row. */
    val swipeActionEnd: Flow<SwipeAction>

    suspend fun setSwipeActionEnd(value: SwipeAction)

    /** Bottom destination the app opens on. */
    val defaultDestination: Flow<StartDestination>

    suspend fun setDefaultDestination(value: StartDestination)

    /** Inbox category filter applied at startup; null means All. */
    val defaultInboxFilter: Flow<Category?>

    suspend fun setDefaultInboxFilter(value: Category?)

    /** Last Finance pill the user selected (persisted UI state, not shown in Settings). */
    val financeTab: Flow<FinanceTab>

    suspend fun setFinanceTab(value: FinanceTab)

    /** Show a parsed amount in transaction notifications instead of the raw message. */
    val transactionNotifications: Flow<Boolean>

    suspend fun setTransactionNotifications(value: Boolean)

    /** Backing plate drawn behind bundled sender logos. */
    val logoBackground: Flow<LogoBackground>

    suspend fun setLogoBackground(value: LogoBackground)

    /**
     * Id of the newest OTP message the user has handled (copied or dismissed)
     * from the inbox banner; 0 when none. Message ids are monotonically
     * increasing, so the banner hides this id and everything older forever —
     * across navigation, restarts and re-categorization — while a newer OTP
     * still surfaces.
     */
    val handledOtpMessageId: Flow<Long>

    suspend fun setHandledOtpMessageId(value: Long)

    /**
     * User-chosen order of the Inbox category pills. Always emits every
     * [Category] exactly once: unknown stored names are dropped and entries
     * missing from the stored list are appended in declaration order, so a
     * pill added in a future version can never disappear. Default is the
     * enum's declaration order.
     */
    val inboxPillOrder: Flow<List<Category>>

    suspend fun setInboxPillOrder(value: List<Category>)

    /** User-chosen order of the Finance pills; same guarantees as [inboxPillOrder]. */
    val financePillOrder: Flow<List<FinanceTab>>

    suspend fun setFinancePillOrder(value: List<FinanceTab>)

    /**
     * User-chosen order of the Alerts filter pills; same guarantees as
     * [inboxPillOrder]. Persisted as enum names, so [AlertFilter] staying in
     * the ui layer costs nothing at the storage level.
     */
    val alertsPillOrder: Flow<List<AlertFilter>>

    suspend fun setAlertsPillOrder(value: List<AlertFilter>)
}
