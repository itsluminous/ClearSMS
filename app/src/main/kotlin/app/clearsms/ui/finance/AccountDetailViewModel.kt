package app.clearsms.ui.finance

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.clearsms.data.db.TransactionEntity
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.data.repository.FinanceRepository
import app.clearsms.di.IoDispatcher
import app.clearsms.domain.model.TransactionType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth
import javax.inject.Inject

/** Transaction direction filter for the detail view. */
enum class TxFilter {
    ALL,
    DEBITED,
    CREDITED,
}

/** One month group: header totals plus the month's transactions. */
data class MonthGroup(
    val month: YearMonth,
    val credits: Double,
    val debits: Double,
    val transactions: List<TransactionEntity>,
)

data class AccountDetailUiState(
    val accountNumber: String = "",
    val bankName: String = "",
    val filter: TxFilter = TxFilter.ALL,
    val chart: List<MonthlyTotals> = emptyList(),
    val groups: List<MonthGroup> = emptyList(),
    /** True while more transactions exist beyond the current page. */
    val hasMoreTransactions: Boolean = false,
    /**
     * UNFILTERED rows currently loaded (the page size the limit governs).
     * The load-more spinner must stop against THIS, not the filtered
     * on-screen count: under a Credit/Debit filter the visible count can
     * never reach the unfiltered limit, which kept the spinner alive
     * forever (the reported infinite Load more).
     */
    val loadedCount: Int = 0,
    /** True while the next requested page is still resolving. */
    val isLoadingMore: Boolean = false,
    /** Mirrors Settings → Appearance → Show logos and contact photos. */
    val showRichAvatars: Boolean = true,
    /** True when Settings → Privacy → Show balance is OFF (masking active). */
    val balanceGated: Boolean = false,
    /** True when balances may render: setting ON, or unlocked this session. */
    val balancesRevealed: Boolean = true,
    val loaded: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AccountDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val financeRepository: FinanceRepository,
        settingsRepository: SettingsRepository,
        private val messageLookup: MessageLookup,
        private val balanceVisibility: BalanceVisibility,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val accountNumber: String = checkNotNull(savedStateHandle["accountNumber"])
        private val bankName: String = savedStateHandle.get<String>("bank").orEmpty()

        private val filter = MutableStateFlow(TxFilter.ALL)

        /** Growing LIMIT for the transaction list (the chart always sees all months). */
        private val txLimit = MutableStateFlow(TransactionPaging.PAGE_SIZE)
        private val loadingMore = MutableStateFlow(false)

        val uiState: StateFlow<AccountDetailUiState> =
            combine(
                txLimit
                    .flatMapLatest { limit ->
                        combine(
                            financeRepository.observeTransactionsByAccount(accountNumber, bankName),
                            financeRepository.observeTransactionsByAccount(accountNumber, bankName, limit),
                            filter,
                        ) { allTransactions, page, currentFilter ->
                            buildState(allTransactions, page, currentFilter)
                        }
                    }.onEach { state ->
                        if (state.loadedCount >= txLimit.value || !state.hasMoreTransactions) loadingMore.value = false
                    },
                loadingMore,
                settingsRepository.showRichAvatars,
                settingsRepository.showBalance,
                balanceVisibility.revealed,
            ) { state, pending, richAvatars, showBalance, revealed ->
                state.copy(
                    isLoadingMore = pending && state.hasMoreTransactions,
                    showRichAvatars = richAvatars,
                    balanceGated = !showBalance,
                    balancesRevealed = showBalance || revealed,
                )
            }.flowOn(ioDispatcher)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountDetailUiState())

        /** Called only after a successful device-lock authentication. */
        fun revealBalances() = balanceVisibility.reveal()

        /** Re-masks immediately (eye tap while revealed); no auth needed to hide. */
        fun concealBalances() = balanceVisibility.conceal()

        private fun buildState(
            allTransactions: List<TransactionEntity>,
            page: List<TransactionEntity>,
            currentFilter: TxFilter,
        ): AccountDetailUiState {
            val filtered =
                when (currentFilter) {
                    TxFilter.ALL -> page
                    TxFilter.DEBITED -> page.filter { it.type == TransactionType.DEBIT }
                    TxFilter.CREDITED -> page.filter { it.type == TransactionType.CREDIT }
                }
            return AccountDetailUiState(
                accountNumber = accountNumber,
                bankName = bankName,
                filter = currentFilter,
                chart = MonthlyAggregation.lastMonths(allTransactions, months = 6, endMonth = YearMonth.now()),
                groups =
                    MonthlyAggregation.groupByMonth(filtered).map { (month, txs) ->
                        MonthGroup(
                            month = month,
                            credits = txs.filter { it.type == TransactionType.CREDIT }.sumOf { it.amount },
                            debits = txs.filter { it.type == TransactionType.DEBIT }.sumOf { it.amount },
                            transactions = txs,
                        )
                    },
                hasMoreTransactions = TransactionPaging.hasMore(shown = page.size, total = allTransactions.size),
                loadedCount = page.size,
                loaded = true,
            )
        }

        /** Appends the next page of transactions to the list. */
        fun loadMore() {
            loadingMore.value = true
            txLimit.value = TransactionPaging.nextLimit(txLimit.value)
        }

        fun setFilter(value: TxFilter) {
            filter.value = value
        }

        fun addNote(
            transactionId: Long,
            note: String,
        ) {
            viewModelScope.launch(ioDispatcher) {
                financeRepository.addNote(transactionId, note.ifBlank { null })
            }
        }

        /** Loads the full SMS body behind a transaction for the expanded row. */
        suspend fun smsBodyFor(rawSmsId: Long): String? =
            withContext(ioDispatcher) {
                messageLookup.byId(rawSmsId)?.body
            }

        /** Conversation target for the SMS behind [rawSmsId]; null when it was deleted. */
        suspend fun sourceMessageFor(rawSmsId: Long): MessageRef? =
            withContext(ioDispatcher) {
                SourceMessageResolver.resolve(messageLookup.byId(rawSmsId))
            }
    }
