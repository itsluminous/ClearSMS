package app.clearsms.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.clearsms.data.backup.BackupManager
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.data.repository.MessageRepository
import app.clearsms.di.IoDispatcher
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.NotificationAction
import app.clearsms.domain.model.OtpAutoDeletePolicy
import app.clearsms.domain.model.OtpDisplaySize
import app.clearsms.domain.model.StartDestination
import app.clearsms.domain.model.SummaryFrequency
import app.clearsms.domain.model.SwipeAction
import app.clearsms.domain.model.ThemeMode
import app.clearsms.ui.common.BackupFrequency
import app.clearsms.ui.common.UiPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val showTransactionDetails: Boolean = true,
    val showRichAvatars: Boolean = true,
    val deliveryReports: Boolean = false,
    val summaryFrequency: SummaryFrequency = SummaryFrequency.OFF,
    val notificationActions: Set<NotificationAction> = setOf(NotificationAction.MARK_READ, NotificationAction.REPLY),
    val transactionNotifications: Boolean = true,
    val swipeActionStart: SwipeAction = SwipeAction.ARCHIVE,
    val swipeActionEnd: SwipeAction = SwipeAction.DELETE,
    val defaultDestination: StartDestination = StartDestination.INBOX,
    val defaultInboxFilter: Category? = Category.IMPORTANT,
    val otpAutoCopy: Boolean = true,
    val otpAutoDeletePolicy: OtpAutoDeletePolicy = OtpAutoDeletePolicy.NEVER,
    val otpDisplaySize: OtpDisplaySize = OtpDisplaySize.DEFAULT,
    val signature: String = "",
    val backupFrequency: BackupFrequency = BackupFrequency.OFF,
    val blockedSenders: List<String> = emptyList(),
    val sorting: Boolean = false,
    val busy: Boolean = false,
)

/** One-off outcomes surfaced as snackbars. */
sealed interface SettingsEvent {
    data object BackupDone : SettingsEvent

    data object BackupFailed : SettingsEvent

    data object RestoreDone : SettingsEvent

    data object RestoreFailed : SettingsEvent

    data object SortDone : SettingsEvent
}

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settings: SettingsRepository,
        private val uiPrefs: UiPrefs,
        private val messageRepository: MessageRepository,
        private val backupManager: BackupManager,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val sorting = MutableStateFlow(false)
        private val busy = MutableStateFlow(false)

        private val events = MutableSharedFlow<SettingsEvent>()
        val eventFlow: SharedFlow<SettingsEvent> = events

        private data class AppearanceState(
            val theme: ThemeMode,
            val dynamicColor: Boolean,
            val showTransactionDetails: Boolean,
            val showRichAvatars: Boolean,
        )

        private data class NotificationState(
            val deliveryReports: Boolean,
            val summaryFrequency: SummaryFrequency,
            val notificationActions: Set<NotificationAction>,
            val transactionNotifications: Boolean,
        )

        private data class GestureStartupState(
            val swipeStart: SwipeAction,
            val swipeEnd: SwipeAction,
            val destination: StartDestination,
            val inboxFilter: Category?,
        )

        private val appearance =
            combine(
                settings.theme,
                uiPrefs.dynamicColor,
                settings.showTransactionDetails,
                settings.showRichAvatars,
                ::AppearanceState,
            )
        private val notifications =
            combine(
                uiPrefs.deliveryReports,
                settings.summaryFrequency,
                settings.notificationActions,
                settings.transactionNotifications,
                ::NotificationState,
            )
        private val gestureStartup =
            combine(
                settings.swipeActionStart,
                settings.swipeActionEnd,
                settings.defaultDestination,
                settings.defaultInboxFilter,
                ::GestureStartupState,
            )
        private val otp =
            combine(settings.otpAutoCopy, settings.otpAutoDeletePolicy, settings.otpDisplaySize, ::Triple)
        private val other =
            combine(settings.signature, uiPrefs.backupFrequency, uiPrefs.blockedSenders, ::Triple)

        val uiState: StateFlow<SettingsUiState> =
            combine(
                combine(appearance, gestureStartup, ::Pair),
                notifications,
                otp,
                other,
                combine(sorting, busy, ::Pair),
            ) {
                    (appearanceState, gestures),
                    notificationState,
                    (autoCopy, autoDelete, size),
                    (signature, backupFreq, blocked),
                    (isSorting, isBusy),
                ->
                SettingsUiState(
                    theme = appearanceState.theme,
                    dynamicColor = appearanceState.dynamicColor,
                    showTransactionDetails = appearanceState.showTransactionDetails,
                    showRichAvatars = appearanceState.showRichAvatars,
                    deliveryReports = notificationState.deliveryReports,
                    summaryFrequency = notificationState.summaryFrequency,
                    notificationActions = notificationState.notificationActions,
                    transactionNotifications = notificationState.transactionNotifications,
                    swipeActionStart = gestures.swipeStart,
                    swipeActionEnd = gestures.swipeEnd,
                    defaultDestination = gestures.destination,
                    defaultInboxFilter = gestures.inboxFilter,
                    otpAutoCopy = autoCopy,
                    otpAutoDeletePolicy = autoDelete,
                    otpDisplaySize = size,
                    signature = signature,
                    backupFrequency = backupFreq,
                    blockedSenders = blocked.sorted(),
                    sorting = isSorting,
                    busy = isBusy,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

        fun setTheme(value: ThemeMode) = launchIo { settings.setTheme(value) }

        fun setDynamicColor(value: Boolean) = launchIo { uiPrefs.setDynamicColor(value) }

        fun setShowTransactionDetails(value: Boolean) = launchIo { settings.setShowTransactionDetails(value) }

        fun setShowRichAvatars(value: Boolean) = launchIo { settings.setShowRichAvatars(value) }

        fun setNotificationActions(value: Set<NotificationAction>) = launchIo { settings.setNotificationActions(value) }

        fun setTransactionNotifications(value: Boolean) = launchIo { settings.setTransactionNotifications(value) }

        fun setSwipeActionStart(value: SwipeAction) = launchIo { settings.setSwipeActionStart(value) }

        fun setSwipeActionEnd(value: SwipeAction) = launchIo { settings.setSwipeActionEnd(value) }

        fun setDefaultDestination(value: StartDestination) = launchIo { settings.setDefaultDestination(value) }

        fun setDefaultInboxFilter(value: Category?) = launchIo { settings.setDefaultInboxFilter(value) }

        fun setDeliveryReports(value: Boolean) = launchIo { uiPrefs.setDeliveryReports(value) }

        fun setSummaryFrequency(value: SummaryFrequency) = launchIo { settings.setSummaryFrequency(value) }

        fun setOtpAutoCopy(value: Boolean) = launchIo { settings.setOtpAutoCopy(value) }

        fun setOtpAutoDeletePolicy(value: OtpAutoDeletePolicy) = launchIo { settings.setOtpAutoDeletePolicy(value) }

        fun setOtpDisplaySize(value: OtpDisplaySize) = launchIo { settings.setOtpDisplaySize(value) }

        fun setSignature(value: String) = launchIo { settings.setSignature(value) }

        fun setBackupFrequency(value: BackupFrequency) = launchIo { uiPrefs.setBackupFrequency(value) }

        fun blockSender(sender: String) =
            launchIo {
                messageRepository.setBlocked(sender, true)
                uiPrefs.setSenderBlocked(sender, true)
            }

        fun unblockSender(sender: String) =
            launchIo {
                messageRepository.setBlocked(sender, false)
                uiPrefs.setSenderBlocked(sender, false)
            }

        fun backupTo(uri: Uri) {
            launchIo {
                busy.value = true
                try {
                    context.contentResolver.openOutputStream(uri)?.use { backupManager.exportTo(it) }
                    events.emit(SettingsEvent.BackupDone)
                } catch (_: Exception) {
                    events.emit(SettingsEvent.BackupFailed)
                } finally {
                    busy.value = false
                }
            }
        }

        fun restoreFrom(uri: Uri) {
            launchIo {
                busy.value = true
                try {
                    context.contentResolver.openInputStream(uri)?.use { backupManager.importFrom(it) }
                    events.emit(SettingsEvent.RestoreDone)
                } catch (_: Exception) {
                    events.emit(SettingsEvent.RestoreFailed)
                } finally {
                    busy.value = false
                }
            }
        }

        /** Settings → Sort: re-runs categorization over all stored messages. */
        fun sortInboxAgain() {
            launchIo {
                sorting.value = true
                try {
                    messageRepository.recategorizeAll()
                    events.emit(SettingsEvent.SortDone)
                } finally {
                    sorting.value = false
                }
            }
        }

        private fun launchIo(block: suspend () -> Unit) {
            viewModelScope.launch(ioDispatcher) { block() }
        }
    }
