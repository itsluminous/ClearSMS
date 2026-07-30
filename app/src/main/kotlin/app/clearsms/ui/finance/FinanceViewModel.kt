package app.clearsms.ui.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.clearsms.data.db.AccountEntity
import app.clearsms.data.db.TransactionEntity
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.data.repository.FinanceRepository
import app.clearsms.di.IoDispatcher
import app.clearsms.domain.model.AccountType
import app.clearsms.domain.model.FinanceTab
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
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

/** One credit card with its derived display figures (see [CreditCardFigures]). */
data class CreditCardItem(
    val account: AccountEntity,
    val figures: CardFigures,
)

data class FinanceUiState(
    val monthNet: Double = 0.0,
    val monthDebits: Double = 0.0,
    val monthCredits: Double = 0.0,
    /** Counted transactions inside the current month (all / debit / credit). */
    val monthTxCount: Int = 0,
    val monthDebitCount: Int = 0,
    val monthCreditCount: Int = 0,
    /** Rows excluded from the totals (self-transfers, card-bill payments). */
    val monthExcludedCount: Int = 0,
    val monthExcludedTotal: Double = 0.0,
    val bankAccounts: List<AccountEntity> = emptyList(),
    /** Accounts with no update for over a year — behind "Show older". */
    val staleBankAccounts: List<AccountEntity> = emptyList(),
    val creditCards: List<CreditCardItem> = emptyList(),
    /** Cards with no update for over a year — behind "Show older". */
    val staleCreditCards: List<CreditCardItem> = emptyList(),
    val cardsAboveSafeLimit: Int = 0,
    /** Bounded page of newest transactions — grows via "load more". */
    val latestTransactions: List<TransactionEntity> = emptyList(),
    /** All prepaid-recharge transactions, newest first (Recharges pill). */
    val rechargeTransactions: List<TransactionEntity> = emptyList(),
    /** True while more transactions exist beyond the current page. */
    val hasMoreTransactions: Boolean = false,
    /** True while the next requested page is still resolving. */
    val isLoadingMore: Boolean = false,
    /** Badge counts per pill: accounts / cards / transactions this month. */
    val pillCounts: Map<FinanceTab, Int> = emptyMap(),
    /** Pill order configured in Settings; empty means declaration order. */
    val pillOrder: List<FinanceTab> = emptyList(),
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
class FinanceViewModel
    @Inject
    constructor(
        private val financeRepository: FinanceRepository,
        private val settingsRepository: SettingsRepository,
        private val messageLookup: MessageLookup,
        private val balanceVisibility: BalanceVisibility,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        /** Persisted pill selection — restored across app restarts. */
        val selectedTab: StateFlow<FinanceTab> =
            settingsRepository.financeTab
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FinanceTab.ACCOUNTS)

        /** Growing LIMIT for the latest-transactions page. */
        private val txLimit = MutableStateFlow(TransactionPaging.PAGE_SIZE)
        private val loadingMore = MutableStateFlow(false)

        /**
         * Whether the month-summary banner shows its inline breakdown.
         * Deliberately separate from [selectedTab]: expanding the banner is a
         * disclosure gesture, not a navigation, so it never disturbs the
         * persisted pill selection.
         */
        private val summaryExpandedFlow = MutableStateFlow(false)
        val summaryExpanded: StateFlow<Boolean> = summaryExpandedFlow

        /** Toggles the summary banner's inline breakdown open/closed. */
        fun toggleSummaryBreakdown() {
            summaryExpandedFlow.value = !summaryExpandedFlow.value
        }

        /**
         * "Show older" disclosure per section — deliberately NOT persisted:
         * dormant rows should re-collapse on the next session, the state
         * only survives recomposition and config changes within one.
         */
        private val showOlderAccountsFlow = MutableStateFlow(false)
        val showOlderAccounts: StateFlow<Boolean> = showOlderAccountsFlow

        private val showOlderCardsFlow = MutableStateFlow(false)
        val showOlderCards: StateFlow<Boolean> = showOlderCardsFlow

        fun toggleShowOlderAccounts() {
            showOlderAccountsFlow.value = !showOlderAccountsFlow.value
        }

        fun toggleShowOlderCards() {
            showOlderCardsFlow.value = !showOlderCardsFlow.value
        }

        val uiState: StateFlow<FinanceUiState> =
            combine(
                txLimit
                    .flatMapLatest { limit ->
                        combine(
                            financeRepository.observeTransactions(),
                            financeRepository.observeAccounts(),
                            financeRepository.observeLatestTransactions(limit),
                        ) { transactions, accounts, page ->
                            buildState(transactions, accounts, page)
                        }
                    }.onEach { state ->
                        // The requested page arrived (or the list is exhausted) — clear the pending flag.
                        val satisfied = state.latestTransactions.size >= txLimit.value || !state.hasMoreTransactions
                        if (satisfied) loadingMore.value = false
                    },
                loadingMore,
                settingsRepository.showRichAvatars,
                settingsRepository.showBalance,
                balanceVisibility.revealed,
            ) { state, pending, richAvatars, showBalance, revealed ->
                state.copy(
                    isLoadingMore =
                        pending &&
                            state.hasMoreTransactions &&
                            state.latestTransactions.size < txLimit.value,
                    showRichAvatars = richAvatars,
                    balanceGated = !showBalance,
                    balancesRevealed = showBalance || revealed,
                )
            }.flowOn(ioDispatcher)
                .combine(settingsRepository.financePillOrder) { state, order -> state.copy(pillOrder = order) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FinanceUiState())

        private fun buildState(
            transactions: List<TransactionEntity>,
            accounts: List<AccountEntity>,
            page: List<TransactionEntity>,
        ): FinanceUiState {
            val zone = ZoneId.systemDefault()
            val currentMonth = YearMonth.now(zone)
            val monthTxs =
                transactions.filter {
                    YearMonth.from(Instant.ofEpochMilli(it.timestamp).atZone(zone)) == currentMonth
                }
            val cards =
                accounts
                    .filter { it.type == AccountType.CREDIT_CARD }
                    .map { account ->
                        CreditCardItem(
                            account = account,
                            figures =
                                CreditCardFigures.compute(
                                    availableLimit = account.availableLimit,
                                    lastKnownBalance = account.lastKnownBalance,
                                    totalLimit = account.creditLimit,
                                ),
                        )
                    }
            val total = transactions.size
            // Headline totals go through MonthSummary so self-transfers and
            // card-bill payments are not double counted (see its kdoc).
            val monthTotals = MonthSummary.compute(monthTxs, MonthSummary.cardIdentity(accounts))
            val nowMs = System.currentTimeMillis()
            val accountSplit =
                StaleAccounts.partition(
                    items = accounts.filter { it.type != AccountType.CREDIT_CARD },
                    nowMs = nowMs,
                ) { it.lastUpdated }
            val cardSplit = StaleAccounts.partition(items = cards, nowMs = nowMs) { it.account.lastUpdated }
            return FinanceUiState(
                monthNet = monthTotals.net,
                monthDebits = monthTotals.debits,
                monthCredits = monthTotals.credits,
                monthTxCount = monthTotals.txCount,
                monthDebitCount = monthTotals.debitCount,
                monthCreditCount = monthTotals.creditCount,
                monthExcludedCount = monthTotals.excludedCount,
                monthExcludedTotal = monthTotals.excludedTotal,
                bankAccounts = accountSplit.active,
                staleBankAccounts = accountSplit.stale,
                creditCards = cardSplit.active,
                staleCreditCards = cardSplit.stale,
                cardsAboveSafeLimit = Utilization.countAboveSafeLimit(cards.map { it.figures.utilization }),
                latestTransactions = page,
                rechargeTransactions =
                    transactions
                        .filter(RechargeTransactions::isRecharge)
                        .sortedByDescending { it.timestamp },
                hasMoreTransactions = TransactionPaging.hasMore(shown = page.size, total = total),
                isLoadingMore = false,
                pillCounts = FinancePills.counts(accounts, transactions, currentMonth, zone),
                loaded = true,
            )
        }

        /** Appends the next page of transactions to the list. */
        fun loadMore() {
            loadingMore.value = true
            txLimit.value = TransactionPaging.nextLimit(txLimit.value)
        }

        /** Conversation target for the SMS behind [rawSmsId]; null when it was deleted. */
        suspend fun sourceMessageFor(rawSmsId: Long): MessageRef? =
            withContext(ioDispatcher) {
                SourceMessageResolver.resolve(messageLookup.byId(rawSmsId))
            }

        /** Conversation target for the message behind an account's most recent update. */
        suspend fun sourceMessageForAccount(account: AccountEntity): MessageRef? =
            withContext(ioDispatcher) {
                val latest = financeRepository.latestTransactionForAccount(account.accountNumber, account.bankName)
                SourceMessageResolver.resolve(latest?.let { messageLookup.byId(it.rawSmsId) })
            }

        fun setTab(tab: FinanceTab) {
            viewModelScope.launch(ioDispatcher) { settingsRepository.setFinanceTab(tab) }
        }

        /** Called only after a successful device-lock authentication. */
        fun revealBalances() = balanceVisibility.reveal()

        /** Re-masks immediately (eye tap while revealed); no auth needed to hide. */
        fun concealBalances() = balanceVisibility.conceal()
    }
