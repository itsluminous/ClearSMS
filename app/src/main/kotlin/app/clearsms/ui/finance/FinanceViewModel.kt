package app.clearsms.ui.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.clearsms.data.db.AccountEntity
import app.clearsms.data.db.TransactionEntity
import app.clearsms.data.repository.FinanceRepository
import app.clearsms.di.IoDispatcher
import app.clearsms.domain.model.AccountType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

/** One credit card with its derived utilization. */
data class CreditCardItem(
    val account: AccountEntity,
    val outstanding: Double,
    /** 0..1 fraction of the limit used; null when no limit is set. */
    val utilization: Float?,
    val level: UtilizationLevel,
)

data class FinanceUiState(
    val monthNet: Double = 0.0,
    val monthDebits: Double = 0.0,
    val monthCredits: Double = 0.0,
    val bankAccounts: List<AccountEntity> = emptyList(),
    val creditCards: List<CreditCardItem> = emptyList(),
    val cardsAboveSafeLimit: Int = 0,
    val latestTransactions: List<TransactionEntity> = emptyList(),
    val loaded: Boolean = false,
)

@HiltViewModel
class FinanceViewModel
    @Inject
    constructor(
        private val financeRepository: FinanceRepository,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        val uiState: StateFlow<FinanceUiState> =
            combine(
                financeRepository.observeTransactions(),
                financeRepository.observeAccounts(),
            ) { transactions, accounts ->
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
                            val outstanding = account.lastKnownBalance ?: 0.0
                            val fraction = Utilization.fraction(outstanding, account.creditLimit)
                            CreditCardItem(
                                account = account,
                                outstanding = outstanding,
                                utilization = fraction,
                                level = fraction?.let(Utilization::level) ?: UtilizationLevel.NORMAL,
                            )
                        }
                FinanceUiState(
                    monthNet = MonthlyAggregation.net(monthTxs),
                    monthDebits = monthTxs.filter { it.type == app.clearsms.domain.model.TransactionType.DEBIT }.sumOf { it.amount },
                    monthCredits = monthTxs.filter { it.type == app.clearsms.domain.model.TransactionType.CREDIT }.sumOf { it.amount },
                    bankAccounts = accounts.filter { it.type != AccountType.CREDIT_CARD },
                    creditCards = cards,
                    cardsAboveSafeLimit = Utilization.countAboveSafeLimit(cards.map { it.utilization }),
                    latestTransactions = transactions.take(LATEST_TRANSACTIONS),
                    loaded = true,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FinanceUiState())

        fun setCardLimit(
            accountId: Long,
            limit: Double?,
        ) {
            viewModelScope.launch(ioDispatcher) { financeRepository.setCardLimit(accountId, limit) }
        }

        private companion object {
            const val LATEST_TRANSACTIONS = 20
        }
    }
