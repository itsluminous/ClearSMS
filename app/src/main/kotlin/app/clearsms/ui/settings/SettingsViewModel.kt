package app.clearsms.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.clearsms.data.backup.BackupManager
import app.clearsms.data.backup.RestoreResult
import app.clearsms.data.backup.SettingsBackupManager
import app.clearsms.data.backup.SettingsRestoreResult
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.data.repository.MessageRepository
import app.clearsms.data.repository.SenderBlocker
import app.clearsms.di.IoDispatcher
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
import app.clearsms.ui.common.BackupFrequency
import app.clearsms.ui.common.UiPrefs
import app.clearsms.ui.composemsg.ContactSuggestion
import app.clearsms.ui.composemsg.ContactSuggestions
import app.clearsms.ui.composemsg.contactSuggestionFeed
import app.clearsms.ui.finance.BalanceVisibility
import app.clearsms.work.BackupWorker
import app.clearsms.work.RecategorizeWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val showTransactionDetails: Boolean = true,
    /** Recycle bin for deleted messages (30-day retention); default ON. */
    val recycleBinEnabled: Boolean = true,
    val showRichAvatars: Boolean = true,
    /** Privacy gate: false masks Finance balances behind the device lock. */
    val showBalance: Boolean = true,
    val deliveryReports: Boolean = false,
    val notificationActions: Set<NotificationAction> = setOf(NotificationAction.MARK_READ, NotificationAction.REPLY),
    val transactionNotifications: Boolean = true,
    val logoBackground: LogoBackground = LogoBackground.NONE,
    val swipeActionStart: SwipeAction = SwipeAction.ARCHIVE,
    val swipeActionEnd: SwipeAction = SwipeAction.DELETE,
    val defaultDestination: StartDestination = StartDestination.INBOX,
    val defaultInboxFilter: Category? = Category.IMPORTANT,
    val defaultFinanceFilter: FinanceTab = FinanceTab.ACCOUNTS,
    val otpAutoCopy: Boolean = true,
    val otpAutoDeletePolicy: OtpAutoDeletePolicy = OtpAutoDeletePolicy.NEVER,
    val otpDisplaySize: OtpDisplaySize = OtpDisplaySize.DEFAULT,
    val signature: String = "",
    val backupFrequency: BackupFrequency = BackupFrequency.OFF,
    /** SAF tree uri of the automatic-backup directory; null until the user picks one. */
    val backupDirectoryUri: String? = null,
    /** Raised by the worker when the chosen directory vanished or its grant was revoked. */
    val backupDirectoryError: Boolean = false,
    val blockedSenders: List<String> = emptyList(),
    /** Keywords that route matching incoming messages straight to the bin. */
    val blockedKeywords: List<String> = emptyList(),
    /** Non-null while a manual re-sort is enqueued/running (drives the inline progress row). */
    val sortProgress: SortProgress? = null,
    val busy: Boolean = false,
)

/** Progress of the manual "Sort inbox again" run, mirrored from WorkManager. */
data class SortProgress(
    val processed: Int,
    val total: Int,
)

/** One-off outcomes surfaced as snackbars. */
sealed interface SettingsEvent {
    data object BackupDone : SettingsEvent

    data object BackupFailed : SettingsEvent

    /** Directory picker cancelled while enabling a frequency: automatic backups stay off. */
    data object BackupDirectoryDeclined : SettingsEvent

    /** Restore succeeded; carries per-table counts and any defaulted/skipped tallies. */
    data class RestoreDone(
        val result: RestoreResult,
    ) : SettingsEvent

    /** Restore failed; [reason] is a human-readable cause when known. */
    data class RestoreFailed(
        val reason: String? = null,
    ) : SettingsEvent

    data object SettingsBackupDone : SettingsEvent

    data object SettingsBackupFailed : SettingsEvent

    /** Settings restore succeeded; carries applied/skipped entry counts. */
    data class SettingsRestoreDone(
        val result: SettingsRestoreResult,
    ) : SettingsEvent

    /** Settings restore failed: not a settings backup, or unreadable. */
    data object SettingsRestoreFailed : SettingsEvent

    /** Manual re-sort finished; [count] messages were re-categorized. */
    data class SortDone(
        val count: Int,
    ) : SettingsEvent

    /** Manual OTP cleanup finished; [count] messages were deleted. */
    data class OtpCleared(
        val count: Int,
    ) : SettingsEvent

    /** Manual OTP cleanup found nothing matching the chosen range. */
    data object OtpClearEmpty : SettingsEvent
}

/**
 * A requested-but-unconfirmed manual OTP cleanup: the count backs the
 * confirmation dialog, and the cutoff is frozen at request time so the
 * confirmed deletion targets exactly what was counted.
 */
data class PendingOtpClear(
    val range: ClearOtpRange,
    val cutoffMs: Long,
    val count: Int,
)

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settings: SettingsRepository,
        private val uiPrefs: UiPrefs,
        private val messageRepository: MessageRepository,
        private val senderBlocker: SenderBlocker,
        private val contactSuggestions: ContactSuggestions,
        private val backupManager: BackupManager,
        private val settingsBackupManager: SettingsBackupManager,
        private val workManager: WorkManager,
        private val balanceVisibility: BalanceVisibility,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val busy = MutableStateFlow(false)

        /** Current text in the block dialog's sender field (drives autocomplete). */
        private val senderQuery = MutableStateFlow("")

        /** Non-null while the OTP-cleanup confirmation dialog should be showing. */
        private val pendingOtpClearFlow = MutableStateFlow<PendingOtpClear?>(null)
        val pendingOtpClear: StateFlow<PendingOtpClear?> = pendingOtpClearFlow

        private val events = MutableSharedFlow<SettingsEvent>()
        val eventFlow: SharedFlow<SettingsEvent> = events

        /**
         * The manual re-sort run, observed straight from WorkManager - the
         * ViewModel never owns the work, so progress survives navigation and
         * process death exactly like the initial import.
         */
        private val sortWorkInfo: Flow<WorkInfo?> =
            workManager
                .getWorkInfosForUniqueWorkFlow(RecategorizeWorker.WORK_NAME)
                .map { infos -> infos.firstOrNull() }

        private val sortProgress: Flow<SortProgress?> =
            sortWorkInfo.map { info ->
                when (info?.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED ->
                        SortProgress(
                            processed = info.progress.getInt(RecategorizeWorker.PROGRESS_PROCESSED, 0),
                            total = info.progress.getInt(RecategorizeWorker.PROGRESS_TOTAL, 0),
                        )
                    else -> null
                }
            }

        init {
            // Completion snackbar: emitted once when a run observed active in
            // THIS session finishes successfully (a stale SUCCEEDED record
            // from a previous session stays silent).
            viewModelScope.launch {
                var sawActiveRun = false
                sortWorkInfo.collect { info ->
                    when {
                        info == null -> Unit
                        !info.state.isFinished -> sawActiveRun = true
                        info.state == WorkInfo.State.SUCCEEDED && sawActiveRun -> {
                            sawActiveRun = false
                            events.emit(
                                SettingsEvent.SortDone(info.outputData.getInt(RecategorizeWorker.OUTPUT_COUNT, 0)),
                            )
                        }
                        else -> sawActiveRun = false
                    }
                }
            }
        }

        private data class AppearanceState(
            val theme: ThemeMode,
            val dynamicColor: Boolean,
            val showTransactionDetails: Boolean,
            val showRichAvatars: Boolean,
            val showBalance: Boolean,
            /** Filled by the second combine stage (combine() maxes out at 5 flows). */
            val logoBackground: LogoBackground = LogoBackground.NONE,
            /** Filled by the third combine stage. */
            val recycleBinEnabled: Boolean = true,
        )

        private data class NotificationState(
            val deliveryReports: Boolean,
            val notificationActions: Set<NotificationAction>,
            val transactionNotifications: Boolean,
        )

        private data class GestureStartupState(
            val swipeStart: SwipeAction,
            val swipeEnd: SwipeAction,
            val destination: StartDestination,
            val inboxFilter: Category?,
            val financeFilter: FinanceTab,
        )

        private val appearance =
            combine(
                settings.theme,
                uiPrefs.dynamicColor,
                settings.showTransactionDetails,
                settings.showRichAvatars,
                settings.showBalance,
                ::AppearanceState,
            ).combine(settings.logoBackground) { appearance, logoBackground ->
                appearance.copy(logoBackground = logoBackground)
            }.combine(settings.recycleBinEnabled) { appearance, binEnabled ->
                appearance.copy(recycleBinEnabled = binEnabled)
            }
        private val notifications =
            combine(
                uiPrefs.deliveryReports,
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
                settings.defaultFinanceFilter,
                ::GestureStartupState,
            )
        private val otp =
            combine(settings.otpAutoCopy, settings.otpAutoDeletePolicy, settings.otpDisplaySize, ::Triple)

        private data class OtherState(
            val signature: String,
            val backupFrequency: BackupFrequency,
            val blockedSenders: Set<String>,
            val backupDirectoryUri: String?,
            val backupDirectoryError: Boolean,
            /** Filled by the second combine stage (combine() maxes out at 5 flows). */
            val blockedKeywords: Set<String> = emptySet(),
        )

        private val other =
            combine(
                settings.signature,
                uiPrefs.backupFrequency,
                settings.blockedSenders,
                uiPrefs.backupDirectoryUri,
                uiPrefs.backupDirectoryError,
                ::OtherState,
            ).combine(settings.blockedKeywords) { other, keywords ->
                other.copy(blockedKeywords = keywords)
            }

        val uiState: StateFlow<SettingsUiState> =
            combine(
                combine(appearance, gestureStartup, ::Pair),
                notifications,
                otp,
                other,
                combine(sortProgress, busy, ::Pair),
            ) {
                    (appearanceState, gestures),
                    notificationState,
                    (autoCopy, autoDelete, size),
                    otherState,
                    (sortState, isBusy),
                ->
                SettingsUiState(
                    theme = appearanceState.theme,
                    dynamicColor = appearanceState.dynamicColor,
                    showTransactionDetails = appearanceState.showTransactionDetails,
                    recycleBinEnabled = appearanceState.recycleBinEnabled,
                    showRichAvatars = appearanceState.showRichAvatars,
                    logoBackground = appearanceState.logoBackground,
                    showBalance = appearanceState.showBalance,
                    deliveryReports = notificationState.deliveryReports,
                    notificationActions = notificationState.notificationActions,
                    transactionNotifications = notificationState.transactionNotifications,
                    swipeActionStart = gestures.swipeStart,
                    swipeActionEnd = gestures.swipeEnd,
                    defaultDestination = gestures.destination,
                    defaultInboxFilter = gestures.inboxFilter,
                    defaultFinanceFilter = gestures.financeFilter,
                    otpAutoCopy = autoCopy,
                    otpAutoDeletePolicy = autoDelete,
                    otpDisplaySize = size,
                    signature = otherState.signature,
                    backupFrequency = otherState.backupFrequency,
                    backupDirectoryUri = otherState.backupDirectoryUri,
                    backupDirectoryError = otherState.backupDirectoryError,
                    blockedSenders = otherState.blockedSenders.sorted(),
                    blockedKeywords = otherState.blockedKeywords.sorted(),
                    sortProgress = sortState,
                    busy = isBusy,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

        fun setTheme(value: ThemeMode) = launchIo { settings.setTheme(value) }

        fun setDynamicColor(value: Boolean) = launchIo { uiPrefs.setDynamicColor(value) }

        fun setShowTransactionDetails(value: Boolean) = launchIo { settings.setShowTransactionDetails(value) }

        fun setRecycleBinEnabled(value: Boolean) = launchIo { settings.setRecycleBinEnabled(value) }

        fun setShowRichAvatars(value: Boolean) = launchIo { settings.setShowRichAvatars(value) }

        /**
         * Any write to the balance gate drops the session reveal first, so a
         * previously unlocked session can never survive an OFF→ON→OFF cycle:
         * turning the setting off re-masks immediately.
         */
        fun setShowBalance(value: Boolean) =
            launchIo {
                balanceVisibility.conceal()
                settings.setShowBalance(value)
            }

        fun setNotificationActions(value: Set<NotificationAction>) = launchIo { settings.setNotificationActions(value) }

        fun setTransactionNotifications(value: Boolean) = launchIo { settings.setTransactionNotifications(value) }

        fun setLogoBackground(value: LogoBackground) = launchIo { settings.setLogoBackground(value) }

        fun setInboxPillOrder(value: List<Category>) = launchIo { settings.setInboxPillOrder(value) }

        fun setFinancePillOrder(value: List<FinanceTab>) = launchIo { settings.setFinancePillOrder(value) }

        fun setAlertsPillOrder(value: List<AlertFilter>) = launchIo { settings.setAlertsPillOrder(value) }

        fun resetInboxPillOrder() = setInboxPillOrder(Category.entries.toList())

        fun resetFinancePillOrder() = setFinancePillOrder(FinanceTab.entries.toList())

        fun resetAlertsPillOrder() = setAlertsPillOrder(AlertFilter.entries.toList())

        fun setSwipeActionStart(value: SwipeAction) = launchIo { settings.setSwipeActionStart(value) }

        fun setSwipeActionEnd(value: SwipeAction) = launchIo { settings.setSwipeActionEnd(value) }

        fun setDefaultDestination(value: StartDestination) = launchIo { settings.setDefaultDestination(value) }

        fun setDefaultInboxFilter(value: Category?) = launchIo { settings.setDefaultInboxFilter(value) }

        fun setDefaultFinanceFilter(value: FinanceTab) = launchIo { settings.setDefaultFinanceFilter(value) }

        fun setDeliveryReports(value: Boolean) = launchIo { uiPrefs.setDeliveryReports(value) }

        /** Current pill order per screen, for the reorder dialogs. */
        val inboxPillOrder: StateFlow<List<Category>> =
            settings.inboxPillOrder
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Category.entries.toList())

        val financePillOrder: StateFlow<List<FinanceTab>> =
            settings.financePillOrder
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FinanceTab.entries.toList())

        val alertsPillOrder: StateFlow<List<AlertFilter>> =
            settings.alertsPillOrder
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertFilter.entries.toList())

        fun setOtpAutoCopy(value: Boolean) = launchIo { settings.setOtpAutoCopy(value) }

        fun setOtpAutoDeletePolicy(value: OtpAutoDeletePolicy) = launchIo { settings.setOtpAutoDeletePolicy(value) }

        fun setOtpDisplaySize(value: OtpDisplaySize) = launchIo { settings.setOtpDisplaySize(value) }

        fun setSignature(value: String) = launchIo { settings.setSignature(value) }

        /** Non-null while the directory picker is out for a frequency the user just requested. */
        private val pendingBackupFrequencyFlow = MutableStateFlow<BackupFrequency?>(null)
        val pendingBackupFrequency: StateFlow<BackupFrequency?> = pendingBackupFrequencyFlow

        /**
         * Frequency dialog selection: DAILY/WEEKLY only activate once a
         * backup directory is granted. With no directory yet, the setting is
         * NOT written - the screen launches the tree picker and the outcome
         * of [onBackupDirectoryPicked] decides.
         */
        fun requestBackupFrequency(value: BackupFrequency) {
            launchIo {
                val hasDirectory = uiPrefs.backupDirectoryUri.first() != null
                when (val outcome = BackupDirectoryGate.onFrequencySelected(value, hasDirectory)) {
                    is BackupDirectoryGate.FrequencyOutcome.Apply -> applyBackupFrequency(outcome.frequency)
                    is BackupDirectoryGate.FrequencyOutcome.NeedDirectory ->
                        pendingBackupFrequencyFlow.value = outcome.pending
                }
            }
        }

        /**
         * Result of the SAF tree picker (the screen has already taken the
         * persistable permission for a non-null [treeUri]). A grant stores
         * the directory, clears any stale worker error, and activates the
         * pending frequency; a cancel leaves the frequency at OFF and tells
         * the user why.
         */
        fun onBackupDirectoryPicked(treeUri: String?) {
            val pending = pendingBackupFrequencyFlow.value
            pendingBackupFrequencyFlow.value = null
            launchIo {
                when (val outcome = BackupDirectoryGate.onDirectoryPicked(treeUri != null, pending)) {
                    is BackupDirectoryGate.PickOutcome.ActivatePending -> {
                        uiPrefs.setBackupDirectoryUri(treeUri)
                        uiPrefs.setBackupDirectoryError(false)
                        applyBackupFrequency(outcome.frequency)
                    }
                    BackupDirectoryGate.PickOutcome.LocationUpdated -> {
                        uiPrefs.setBackupDirectoryUri(treeUri)
                        uiPrefs.setBackupDirectoryError(false)
                    }
                    BackupDirectoryGate.PickOutcome.RevertedToOff ->
                        events.emit(SettingsEvent.BackupDirectoryDeclined)
                    BackupDirectoryGate.PickOutcome.Dismissed -> Unit
                }
            }
        }

        private suspend fun applyBackupFrequency(value: BackupFrequency) {
            uiPrefs.setBackupFrequency(value)
            // The setting is only honored if it actually drives the
            // schedule: OFF cancels the periodic work, DAILY/WEEKLY enqueue it.
            BackupWorker.applyFrequency(context, value)
        }

        /**
         * Contact autocomplete for the block dialog's sender field, sharing
         * [ContactSuggestions] and compose's 200 ms debounce so both surfaces
         * behave identically. Blocking also accepts bare sender IDs
         * ("JIOPAY"), which simply match no contact - a pick is never
         * required. Without READ_CONTACTS the query fails soft to empty.
         */
        val senderSuggestions: StateFlow<List<ContactSuggestion>> =
            contactSuggestionFeed(senderQuery, contactSuggestions::search)
                .flowOn(ioDispatcher)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        fun onSenderQueryChange(value: String) {
            senderQuery.value = value
        }

        /** Clears the list after a pick (or when the dialog closes). */
        fun clearSenderSuggestions() {
            senderQuery.value = ""
        }

        fun blockSender(sender: String) = launchIo { senderBlocker.block(sender) }

        fun unblockSender(sender: String) = launchIo { senderBlocker.unblock(sender) }

        /**
         * Adds a validated keyword (the dialog runs
         * [app.clearsms.data.prefs.BlockedKeywords.validate] before calling
         * this - blank/1-char keywords and the 100-keyword cap are refused
         * there with an honest message).
         */
        fun addBlockedKeyword(keyword: String) =
            launchIo {
                settings.setBlockedKeywords(settings.blockedKeywords.first() + keyword.trim())
            }

        fun removeBlockedKeyword(keyword: String) =
            launchIo {
                settings.setBlockedKeywords(settings.blockedKeywords.first() - keyword)
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
                    val result =
                        context.contentResolver.openInputStream(uri)?.use { backupManager.importFrom(it) }
                    if (result != null) {
                        events.emit(SettingsEvent.RestoreDone(result))
                    } else {
                        events.emit(SettingsEvent.RestoreFailed())
                    }
                } catch (e: IllegalArgumentException) {
                    // Validation failures (bad file, newer format) carry a reason.
                    events.emit(SettingsEvent.RestoreFailed(e.message))
                } catch (_: Exception) {
                    events.emit(SettingsEvent.RestoreFailed())
                } finally {
                    busy.value = false
                }
            }
        }

        /** Settings → Backup & restore → Back up settings. */
        fun backupSettingsTo(uri: Uri) {
            launchIo {
                busy.value = true
                try {
                    context.contentResolver.openOutputStream(uri)?.use { settingsBackupManager.exportTo(it) }
                    events.emit(SettingsEvent.SettingsBackupDone)
                } catch (_: Exception) {
                    events.emit(SettingsEvent.SettingsBackupFailed)
                } finally {
                    busy.value = false
                }
            }
        }

        /**
         * Settings → Backup & restore → Restore settings. Applied values
         * take effect immediately: every settings Flow re-emits from the
         * DataStore write, so the theme flips live.
         */
        fun restoreSettingsFrom(uri: Uri) {
            launchIo {
                busy.value = true
                try {
                    val result =
                        context.contentResolver.openInputStream(uri)?.use { settingsBackupManager.importFrom(it) }
                    if (result != null) {
                        events.emit(SettingsEvent.SettingsRestoreDone(result))
                    } else {
                        events.emit(SettingsEvent.SettingsRestoreFailed)
                    }
                } catch (_: Exception) {
                    events.emit(SettingsEvent.SettingsRestoreFailed)
                } finally {
                    busy.value = false
                }
            }
        }

        /**
         * Settings → Sort: enqueues the re-categorization worker (unique,
         * KEEP - a tap while one is running is a no-op). Progress flows back
         * through [sortWorkInfo]; this ViewModel never runs the work itself.
         */
        fun sortInboxAgain() {
            RecategorizeWorker.enqueue(workManager)
        }

        /** Cancels a running re-sort; committed pages stay consistent. */
        fun cancelSort() {
            RecategorizeWorker.cancel(workManager)
        }

        /**
         * Settings → OTP → Clear older OTPs: counts the matching messages
         * first. A non-empty match raises the confirmation dialog via
         * [pendingOtpClear]; an empty one reports straight to a snackbar.
         * Nothing is deleted (and nothing is persisted) until [confirmClearOtp].
         */
        fun requestClearOtp(range: ClearOtpRange) {
            launchIo {
                val cutoff = range.cutoffMs(System.currentTimeMillis())
                val count = messageRepository.countOtpOlderThan(cutoff)
                if (count == 0) {
                    events.emit(SettingsEvent.OtpClearEmpty)
                } else {
                    pendingOtpClearFlow.value = PendingOtpClear(range, cutoff, count)
                }
            }
        }

        /** Runs the confirmed one-shot OTP cleanup and reports how many were deleted. */
        fun confirmClearOtp() {
            val pending = pendingOtpClearFlow.value ?: return
            pendingOtpClearFlow.value = null
            launchIo {
                busy.value = true
                try {
                    val deleted = messageRepository.deleteOtpOlderThan(pending.cutoffMs)
                    events.emit(SettingsEvent.OtpCleared(deleted))
                } finally {
                    busy.value = false
                }
            }
        }

        fun dismissClearOtp() {
            pendingOtpClearFlow.value = null
        }

        private fun launchIo(block: suspend () -> Unit) {
            viewModelScope.launch(ioDispatcher) { block() }
        }
    }
