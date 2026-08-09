package app.clearsms.testing

import app.clearsms.data.prefs.SettingsRepository
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
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [SettingsRepository] for view-model tests. */
open class FakeSettingsRepository : SettingsRepository {
    override val theme = MutableStateFlow(ThemeMode.SYSTEM)

    override suspend fun setTheme(value: ThemeMode) = Unit

    override val otpAutoCopy = MutableStateFlow(true)

    override suspend fun setOtpAutoCopy(value: Boolean) = Unit

    override val otpAutoDeletePolicy = MutableStateFlow(OtpAutoDeletePolicy.NEVER)

    override suspend fun setOtpAutoDeletePolicy(value: OtpAutoDeletePolicy) = Unit

    override val otpDisplaySize = MutableStateFlow(OtpDisplaySize.DEFAULT)

    override suspend fun setOtpDisplaySize(value: OtpDisplaySize) {
        otpDisplaySize.value = value
    }

    override val showTransactionDetails = MutableStateFlow(true)

    override suspend fun setShowTransactionDetails(value: Boolean) {
        showTransactionDetails.value = value
    }

    override val recycleBinEnabled = MutableStateFlow(false)

    override suspend fun setRecycleBinEnabled(value: Boolean) {
        recycleBinEnabled.value = value
    }

    override val showBalance = MutableStateFlow(true)

    override suspend fun setShowBalance(value: Boolean) {
        showBalance.value = value
    }

    override val signature = MutableStateFlow("")

    override suspend fun setSignature(value: String) = Unit

    override val onboardingComplete = MutableStateFlow(true)

    override suspend fun setOnboardingComplete(value: Boolean) = Unit

    override val showRichAvatars = MutableStateFlow(true)

    override suspend fun setShowRichAvatars(value: Boolean) {
        showRichAvatars.value = value
    }

    override val notificationActions = MutableStateFlow(emptySet<NotificationAction>())

    override suspend fun setNotificationActions(value: Set<NotificationAction>) = Unit

    override val swipeActionStart = MutableStateFlow(SwipeAction.ARCHIVE)

    override suspend fun setSwipeActionStart(value: SwipeAction) = Unit

    override val swipeActionEnd = MutableStateFlow(SwipeAction.DELETE)

    override suspend fun setSwipeActionEnd(value: SwipeAction) = Unit

    override val defaultDestination = MutableStateFlow(StartDestination.INBOX)

    override suspend fun setDefaultDestination(value: StartDestination) = Unit

    override val defaultInboxFilter = MutableStateFlow<Category?>(null)

    override suspend fun setDefaultInboxFilter(value: Category?) = Unit

    override val defaultFinanceFilter = MutableStateFlow(FinanceTab.ACCOUNTS)

    override suspend fun setDefaultFinanceFilter(value: FinanceTab) {
        defaultFinanceFilter.value = value
    }

    override val transactionNotifications = MutableStateFlow(true)

    override suspend fun setTransactionNotifications(value: Boolean) = Unit

    override val logoBackground = MutableStateFlow(LogoBackground.WHITE)

    override suspend fun setLogoBackground(value: LogoBackground) = Unit

    override val inboxPillOrder = MutableStateFlow(Category.entries.toList())

    override suspend fun setInboxPillOrder(value: List<Category>) = Unit

    override val financePillOrder = MutableStateFlow(FinanceTab.entries.toList())

    override suspend fun setFinancePillOrder(value: List<FinanceTab>) = Unit

    override val alertsPillOrder = MutableStateFlow(AlertFilter.entries.toList())

    override suspend fun setAlertsPillOrder(value: List<AlertFilter>) = Unit

    override val handledOtpMessageId = MutableStateFlow(0L)

    override suspend fun setHandledOtpMessageId(value: Long) {
        handledOtpMessageId.value = value
    }
}
