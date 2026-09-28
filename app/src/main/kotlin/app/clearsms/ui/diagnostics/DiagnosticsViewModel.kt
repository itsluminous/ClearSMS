package app.clearsms.ui.diagnostics

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.clearsms.BuildConfig
import app.clearsms.R
import app.clearsms.ShareIntents
import app.clearsms.data.db.MessageDao
import app.clearsms.data.db.RuleDao
import app.clearsms.data.db.TransactionDao
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.data.repository.RuleRepository
import app.clearsms.di.IoDispatcher
import app.clearsms.diagnostics.Diag
import app.clearsms.diagnostics.DiagField
import app.clearsms.diagnostics.DiagnosticReport
import app.clearsms.diagnostics.ReportWindow
import app.clearsms.diagnostics.SystemState
import app.clearsms.diagnostics.SystemStateCollector
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** What the diagnostics screen renders: the exact text that would be shared. */
data class DiagnosticsUiState(
    val window: ReportWindow = ReportWindow.DEFAULT,
    /**
     * Masked by default: an unmasked report lists every bank, wallet and
     * merchant that texts the user - a financial footprint - and the
     * non-technical users this screen exists for cannot judge that from
     * "VM-HDFCBK". Consistent placeholders keep the sequence readable for a
     * maintainer, who can ask for an unmasked report when a rule needs it.
     */
    val maskSenders: Boolean = true,
    val preview: String = "",
    val zipBytes: Int = 0,
    val loading: Boolean = true,
    val shareFailed: Boolean = false,
)

/**
 * Builds the diagnostic report from [Diag]'s buffer plus a [SystemState]
 * header, and shares the zipped text through the app's FileProvider. The
 * preview text and the zipped text are the same string; nothing is added
 * after the user has seen it.
 */
@HiltViewModel
class DiagnosticsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settingsRepository: SettingsRepository,
        private val ruleRepository: RuleRepository,
        private val messageDao: MessageDao,
        private val ruleDao: RuleDao,
        private val transactionDao: TransactionDao,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val state = MutableStateFlow(DiagnosticsUiState())
        val uiState: StateFlow<DiagnosticsUiState> = state

        private var header: SystemState? = null

        init {
            viewModelScope.launch {
                header = withContext(ioDispatcher) { collectHeader() }
                rebuild()
            }
        }

        fun setWindow(window: ReportWindow) {
            state.update { it.copy(window = window) }
            rebuild()
        }

        fun setMaskSenders(mask: Boolean) {
            state.update { it.copy(maskSenders = mask) }
            rebuild()
        }

        /** Zips the previewed text and returns the chooser intent, or null when the file could not be written. */
        suspend fun buildShareIntent(): Intent? {
            val current = state.value
            if (current.loading || current.preview.isEmpty()) return null
            return withContext(ioDispatcher) {
                try {
                    val dir = File(context.filesDir, SHARE_DIR).apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    // One clock reading names both the zip and the text inside it.
                    val nowMs = System.currentTimeMillis()
                    val file = File(dir, DiagnosticReport.zipName(nowMs))
                    file.outputStream().use { DiagnosticReport.writeZip(current.preview, it, nowMs) }
                    val uri: Uri = FileProvider.getUriForFile(context, AUTHORITY, file)
                    Diag.i(
                        TAG,
                        "report shared",
                        DiagField.label("window", current.window),
                        DiagField.flag("masked", current.maskSenders),
                        DiagField.count("chars", current.preview.length),
                    )
                    ShareIntents.fileChooser(uri, DiagnosticReport.ZIP_MIME, context.getString(R.string.diagnostics_share_title))
                } catch (e: Exception) {
                    Diag.w(TAG, "report share failed", e)
                    state.update { it.copy(shareFailed = true) }
                    null
                }
            }
        }

        private fun rebuild() {
            val head = header ?: return
            val current = state.value
            val nowMs = System.currentTimeMillis()
            val text =
                DiagnosticReport.build(
                    header = head,
                    lines = Diag.buffer.snapshot(),
                    window = current.window,
                    maskSenders = current.maskSenders,
                    nowMs = nowMs,
                )
            state.update {
                it.copy(
                    preview = text,
                    zipBytes = DiagnosticReport.zipBytes(text, nowMs).size,
                    loading = false,
                    shareFailed = false,
                )
            }
        }

        private suspend fun collectHeader(): SystemState {
            val sections = settingsRepository.enabledSections.first()
            val facts =
                SystemStateCollector.AppFacts(
                    inboxSection = sections.inbox,
                    financeSection = sections.finance,
                    alertsSection = sections.alerts,
                    defaultInboxFilter = settingsRepository.defaultInboxFilter.first()?.name ?: "ALL",
                    messageCount = runCatching { messageDao.count() }.getOrDefault(-1),
                    ruleCount = runCatching { ruleDao.getAll().size }.getOrDefault(-1),
                    transactionCount = runCatching { transactionDao.getAll().size }.getOrDefault(-1),
                    bundledRulesVersion = runCatching { ruleRepository.bundledRulesVersion.first() }.getOrNull() ?: "none",
                )
            return SystemStateCollector.collect(context, facts)
        }

        private companion object {
            const val TAG = "Diagnostics"
            const val SHARE_DIR = "diagnostics/share"
            const val AUTHORITY = BuildConfig.APPLICATION_ID + ".fileprovider"
        }
    }
