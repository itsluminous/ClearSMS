package app.clearsms.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.data.repository.MessageRepository
import app.clearsms.data.repository.SearchQueryFormat
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.ConcurrentHashMap
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

/**
 * Chrome state only - results stream separately through [SearchViewModel.pagedResults]
 * so a keystroke never waits on result mapping before echoing in the field.
 */
data class SearchUiState(
    val category: Category? = null,
    val dateFilter: DateFilter = DateFilter.ANY,
    /** True once the (debounced) query is long enough to run. */
    val searched: Boolean = false,
    /** True when there is input but it is below the minimum length. */
    val belowMinLength: Boolean = false,
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
        /**
         * The text field binds to this directly and [onQueryChange] updates
         * it synchronously - the round trip through the debounced pipeline
         * below never delays the echo of a keystroke (the previous design
         * routed the field value through the result-mapping combine on the
         * IO dispatcher, which lagged far enough to reorder fast typing).
         */
        private val queryState = MutableStateFlow("")
        val query: StateFlow<String> = queryState.asStateFlow()

        private val category = MutableStateFlow<Category?>(null)
        private val dateFilter = MutableStateFlow(DateFilter.ANY)

        /** Sender → display cache so paged rows never repeat provider lookups. */
        private val displayCache = ConcurrentHashMap<String, SenderDisplay>()

        private data class Request(
            val query: String,
            val category: Category?,
            val cutoffMs: Long?,
        )

        /**
         * Paged results: keystrokes are debounced, gated on
         * [SearchQueryFormat.MIN_QUERY_LENGTH], and [flatMapLatest] cancels
         * the in-flight page load the moment a newer request arrives. Row
         * mapping (sender resolution, glyph) happens here on IO - never
         * during composition.
         */
        val pagedResults: Flow<PagingData<SearchResultItem>> =
            combine(
                queryState.map { it.trim() }.debounce(DEBOUNCE_MS).distinctUntilChanged(),
                category,
                dateFilter,
            ) { text, cat, date -> Request(text, cat, cutoffMs(date)) }
                .distinctUntilChanged()
                .flatMapLatest { request ->
                    if (!SearchQueryFormat.isSearchable(request.query)) {
                        flowOf(EMPTY_RESULTS)
                    } else {
                        Pager(
                            config =
                                PagingConfig(
                                    pageSize = PAGE_SIZE,
                                    initialLoadSize = PAGE_SIZE * 2,
                                    enablePlaceholders = false,
                                ),
                            pagingSourceFactory = {
                                messageRepository.pagedSearch(request.query, request.category, request.cutoffMs)
                            },
                        ).flow
                    }
                }.map { data -> data.map { it.toResultItem() } }
                .flowOn(ioDispatcher)
                .cachedIn(viewModelScope)

        val uiState: StateFlow<SearchUiState> =
            combine(queryState, category, dateFilter, settings.showRichAvatars) { text, cat, date, richAvatars ->
                val trimmed = text.trim()
                SearchUiState(
                    category = cat,
                    dateFilter = date,
                    searched = SearchQueryFormat.isSearchable(trimmed),
                    belowMinLength = trimmed.isNotEmpty() && !SearchQueryFormat.isSearchable(trimmed),
                    richAvatars = richAvatars,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

        fun onQueryChange(value: String) {
            queryState.value = value
        }

        fun toggleCategory(value: Category) {
            category.value = if (category.value == value) null else value
        }

        fun setDateFilter(value: DateFilter) {
            dateFilter.value = value
        }

        private fun MessageEntity.toResultItem(): SearchResultItem {
            val display =
                displayCache.getOrPut(sender) {
                    resolveSenderDisplay(
                        sender = sender,
                        contactLookup = contactsSource::lookup,
                        directoryLookup = { senderIdStore.lookup(it)?.name },
                    )
                }
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

        companion object {
            const val DEBOUNCE_MS = 300L
            const val PAGE_SIZE = 30

            /** Empty results with settled load states (no eternal spinner). */
            private val EMPTY_RESULTS: PagingData<MessageEntity> =
                PagingData.empty(
                    sourceLoadStates =
                        LoadStates(
                            refresh = LoadState.NotLoading(endOfPaginationReached = true),
                            prepend = LoadState.NotLoading(endOfPaginationReached = true),
                            append = LoadState.NotLoading(endOfPaginationReached = true),
                        ),
                )
        }
    }
