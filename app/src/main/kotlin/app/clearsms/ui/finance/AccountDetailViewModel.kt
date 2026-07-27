package app.clearsms.ui.finance

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.clearsms.data.db.MessageDao
import app.clearsms.data.db.TransactionEntity
import app.clearsms.data.repository.FinanceRepository
import app.clearsms.di.IoDispatcher
import app.clearsms.domain.model.TransactionType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    val loaded: Boolean = false,
)

@HiltViewModel
class AccountDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val financeRepository: FinanceRepository,
        private val messageDao: MessageDao,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val accountNumber: String = checkNotNull(savedStateHandle["accountNumber"])
        private val bankName: String = savedStateHandle.get<String>("bank").orEmpty()

        private val filter = MutableStateFlow(TxFilter.ALL)

        val uiState: StateFlow<AccountDetailUiState> =
            combine(
                financeRepository.observeTransactionsByAccount(accountNumber),
                filter,
            ) { transactions, currentFilter ->
                val filtered =
                    when (currentFilter) {
                        TxFilter.ALL -> transactions
                        TxFilter.DEBITED -> transactions.filter { it.type == TransactionType.DEBIT }
                        TxFilter.CREDITED -> transactions.filter { it.type == TransactionType.CREDIT }
                    }
                AccountDetailUiState(
                    accountNumber = accountNumber,
                    bankName = bankName,
                    filter = currentFilter,
                    chart = MonthlyAggregation.lastMonths(transactions, months = 6, endMonth = YearMonth.now()),
                    groups =
                        MonthlyAggregation.groupByMonth(filtered).map { (month, txs) ->
                            MonthGroup(
                                month = month,
                                credits = txs.filter { it.type == TransactionType.CREDIT }.sumOf { it.amount },
                                debits = txs.filter { it.type == TransactionType.DEBIT }.sumOf { it.amount },
                                transactions = txs,
                            )
                        },
                    loaded = true,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountDetailUiState())

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
                messageDao.getById(rawSmsId)?.body
            }
    }
