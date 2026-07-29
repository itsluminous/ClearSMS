package app.clearsms.testing

import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.FinanceTab
import app.clearsms.domain.model.LogoBackground
import app.clearsms.domain.model.NotificationAction
import app.clearsms.domain.model.OtpAutoDeletePolicy
import app.clearsms.domain.model.OtpDisplaySize
import app.clearsms.domain.model.StartDestination
import app.clearsms.domain.model.SummaryFrequency
import app.clearsms.domain.model.SwipeAction
import app.clearsms.domain.model.ThemeMode
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

    override val summaryFrequency = MutableStateFlow(SummaryFrequency.OFF)

    override suspend fun setSummaryFrequency(value: SummaryFrequency) = Unit

    override val showTransactionDetails = MutableStateFlow(true)

    override suspend fun setShowTransactionDetails(value: Boolean) {
        showTransactionDetails.value = value
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

    override val financeTab = MutableStateFlow(FinanceTab.ACCOUNTS)

    override suspend fun setFinanceTab(value: FinanceTab) {
        financeTab.value = value
    }

    override val transactionNotifications = MutableStateFlow(true)

    override suspend fun setTransactionNotifications(value: Boolean) = Unit

    override val promotionalNotifications = MutableStateFlow(false)

    override suspend fun setPromotionalNotifications(value: Boolean) = Unit

    override val logoBackground = MutableStateFlow(LogoBackground.WHITE)

    override suspend fun setLogoBackground(value: LogoBackground) = Unit

    override val handledOtpMessageId = MutableStateFlow(0L)

    override suspend fun setHandledOtpMessageId(value: Long) {
        handledOtpMessageId.value = value
    }
}
