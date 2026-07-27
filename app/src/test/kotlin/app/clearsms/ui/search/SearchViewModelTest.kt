package app.clearsms.ui.search

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.repository.SearchQueryFormat
import app.clearsms.data.senderid.SenderIdStore
import app.clearsms.domain.model.Category
import app.clearsms.sms.ContactsSource
import app.clearsms.testing.FakeMessageRepository
import app.clearsms.testing.FakeSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Debounce / cancellation / gating semantics of the search pipeline: rapid
 * keystrokes must collapse into one query, and sub-minimum input must never
 * reach the database.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeMessageRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeMessageRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): SearchViewModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return SearchViewModel(
            messageRepository = repository,
            senderIdStore = SenderIdStore(context),
            contactsSource = ContactsSource(context),
            settings = FakeSettingsRepository(),
            ioDispatcher = dispatcher,
        )
    }

    private fun TestScope.collectResults(viewModel: SearchViewModel): Job = launch { viewModel.pagedResults.collect {} }

    @Test
    fun `rapid keystrokes collapse into a single query`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            val job = collectResults(viewModel)

            // Six keystrokes 50 ms apart — well inside the debounce window.
            for (prefix in listOf("S", "Sa", "Sal", "Sala", "Salar", "Salary")) {
                viewModel.onQueryChange(prefix)
                advanceTimeBy(50)
            }
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 100)

            assertThat(repository.pagedSearchCalls.map { it.first }).containsExactly("Salary")
            job.cancel()
        }

    @Test
    fun `queries below the minimum length never reach the repository`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            val job = collectResults(viewModel)

            viewModel.onQueryChange("S")
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 100)

            assertThat(repository.pagedSearchCalls).isEmpty()
            job.cancel()
        }

    @Test
    fun `a stalled pipeline still echoes keystrokes synchronously`() {
        // No dispatcher progress at all — the field value must not lag,
        // which is what scrambled fast typing in the pre-paging design.
        val viewModel = viewModel()
        viewModel.onQueryChange("S")
        assertThat(viewModel.query.value).isEqualTo("S")
        viewModel.onQueryChange("Sa")
        assertThat(viewModel.query.value).isEqualTo("Sa")
    }

    @Test
    fun `category and date filters compose into the repository call`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            val job = collectResults(viewModel)

            viewModel.toggleCategory(Category.IMPORTANT)
            viewModel.setDateFilter(DateFilter.LAST_30_DAYS)
            viewModel.onQueryChange("salary")
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 100)

            val call = repository.pagedSearchCalls.last()
            assertThat(call.first).isEqualTo("salary")
            assertThat(call.second).isEqualTo(Category.IMPORTANT)
            val expectedCutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
            assertThat(call.third).isAtLeast(expectedCutoff - 60_000)
            assertThat(call.third).isAtMost(expectedCutoff + 60_000)
            job.cancel()
        }

    @Test
    fun `a newer query supersedes the in-flight one`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            val job = collectResults(viewModel)

            viewModel.onQueryChange("salary")
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 100)
            viewModel.onQueryChange("credited")
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 100)

            assertThat(repository.pagedSearchCalls.map { it.first })
                .containsExactly("salary", "credited")
                .inOrder()
            job.cancel()
        }

    @Test
    fun `minimum length constant matches the format gate`() {
        assertThat(SearchQueryFormat.MIN_QUERY_LENGTH).isAtLeast(2)
    }
}
