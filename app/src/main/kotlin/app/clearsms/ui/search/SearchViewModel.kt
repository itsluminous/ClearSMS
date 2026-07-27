package app.clearsms.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.data.repository.MessageRepository
import app.clearsms.data.senderid.SenderIdStore
import app.clearsms.di.IoDispatcher
import app.clearsms.domain.model.Category
import app.clearsms.sms.ContactsSource
import app.clearsms.ui.components.BrandGlyph
import app.clearsms.ui.components.SenderDisplay
import app.clearsms.ui.components.brandGlyphFor
import app.clearsms.ui.components.resolveSenderDisplay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Date-range filter presets for search. */
enum class DateFilter {
    ANY,
    LAST_7_DAYS,
    LAST_30_DAYS,
    LAST_YEAR,
}

/** One search hit with its resolved sender display. */
data class SearchResultItem(
    val message: MessageEntity,
    val display: SenderDisplay,
    val glyph: BrandGlyph,
)

data class SearchUiState(
    val query: String = "",
    val category: Category? = null,
    val dateFilter: DateFilter = DateFilter.ANY,
    val results: List<SearchResultItem> = emptyList(),
    val searched: Boolean = false,
    val richAvatars: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        private val messageRepository: MessageRepository,
        private val senderIdStore: SenderIdStore,
        private val contactsSource: ContactsSource,
        settings: SettingsRepository,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val query = MutableStateFlow("")
        private val category = MutableStateFlow<Category?>(null)
        private val dateFilter = MutableStateFlow(DateFilter.ANY)

        private val rawResults =
            query
                .debounce(300)
                .flatMapLatest { text ->
                    if (text.isBlank()) flowOf(emptyList()) else messageRepository.search(text)
                }

        val uiState: StateFlow<SearchUiState> =
            combine(query, category, dateFilter, rawResults, settings.showRichAvatars) {
                    text,
                    cat,
                    date,
                    results,
                    richAvatars,
                ->
                val cutoff = cutoffMs(date)
                SearchUiState(
                    query = text,
                    category = cat,
                    dateFilter = date,
                    results =
                        results
                            .filter { message ->
                                (cat == null || message.category == cat) &&
                                    (cutoff == null || message.timestamp >= cutoff)
                            }.map { it.toResultItem() },
                    searched = text.isNotBlank(),
                    richAvatars = richAvatars,
                )
            }.flowOn(ioDispatcher)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

        fun onQueryChange(value: String) {
            query.value = value
        }

        fun toggleCategory(value: Category) {
            category.value = if (category.value == value) null else value
        }

        fun setDateFilter(value: DateFilter) {
            dateFilter.value = value
        }

        private fun MessageEntity.toResultItem(): SearchResultItem {
            val display =
                resolveSenderDisplay(
                    sender = sender,
                    contactLookup = contactsSource::lookup,
                    directoryLookup = { senderIdStore.lookup(it)?.name },
                )
            return SearchResultItem(
                message = this,
                display = display,
                glyph = brandGlyphFor(subCategory, display.name),
            )
        }

        private fun cutoffMs(filter: DateFilter): Long? {
            val day = 24L * 60 * 60 * 1000
            val now = System.currentTimeMillis()
            return when (filter) {
                DateFilter.ANY -> null
                DateFilter.LAST_7_DAYS -> now - 7 * day
                DateFilter.LAST_30_DAYS -> now - 30 * day
                DateFilter.LAST_YEAR -> now - 365 * day
            }
        }
    }
