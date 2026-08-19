package app.clearsms.ui.composemsg

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Contact autocomplete behaviour, shared by the compose screen's recipient
 * field and the Settings block list (so the two can't drift apart).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactSuggestionFeedTest {
    private val alice = ContactSuggestion(name = "Alice Kumar", number = "+919000000001")
    private val amit = ContactSuggestion(name = "Amit Rao", number = "+919000000002")

    private fun directory(vararg contacts: ContactSuggestion): suspend (String) -> List<ContactSuggestion> =
        { query -> contacts.filter { it.name.contains(query, ignoreCase = true) || it.number.contains(query) } }

    @Test
    fun `an alphabetic query yields matching contacts`() =
        runTest {
            val query = MutableStateFlow("")
            val results = mutableListOf<List<ContactSuggestion>>()
            val job = launch { contactSuggestionFeed(query, directory(alice, amit)).toList(results) }

            query.value = "am"
            advanceTimeBy(SUGGESTION_DEBOUNCE_MS + 1)

            assertThat(results.last()).containsExactly(amit)
            job.cancel()
        }

    @Test
    fun `a blank query never queries the directory`() =
        runTest {
            var searched = 0
            val query = MutableStateFlow("")
            val results = mutableListOf<List<ContactSuggestion>>()
            val job =
                launch {
                    contactSuggestionFeed(query, {
                        searched++
                        listOf(alice)
                    }).toList(results)
                }

            advanceTimeBy(SUGGESTION_DEBOUNCE_MS + 1)
            query.value = "   "
            advanceTimeBy(SUGGESTION_DEBOUNCE_MS + 1)

            assertThat(searched).isEqualTo(0)
            assertThat(results.last()).isEmpty()
            job.cancel()
        }

    @Test
    fun `rapid typing debounces to a single search`() =
        runTest {
            var searched = 0
            val query = MutableStateFlow("")
            val job =
                launch {
                    contactSuggestionFeed(query, {
                        searched++
                        listOf(alice)
                    }).toList(mutableListOf())
                }

            "alice".forEachIndexed { index, _ ->
                query.value = "alice".take(index + 1)
                advanceTimeBy(20) // faster than the debounce window
            }
            advanceTimeBy(SUGGESTION_DEBOUNCE_MS + 1)

            assertThat(searched).isEqualTo(1)
            job.cancel()
        }

    @Test
    fun `a numeric query matches by number - blocking a bare number still autocompletes`() =
        runTest {
            val query = MutableStateFlow("")
            val results = mutableListOf<List<ContactSuggestion>>()
            val job = launch { contactSuggestionFeed(query, directory(alice, amit)).toList(results) }

            query.value = "9000000002"
            advanceTimeBy(SUGGESTION_DEBOUNCE_MS + 1)

            assertThat(results.last()).containsExactly(amit)
            job.cancel()
        }

    @Test
    fun `a sender id matches nothing and blocking it stays possible`() =
        runTest {
            val query = MutableStateFlow("")
            val results = mutableListOf<List<ContactSuggestion>>()
            val job = launch { contactSuggestionFeed(query, directory(alice, amit)).toList(results) }

            query.value = "JIOPAY"
            advanceTimeBy(SUGGESTION_DEBOUNCE_MS + 1)

            // No suggestions, and nothing here gates the Block action.
            assertThat(results.last()).isEmpty()
            job.cancel()
        }

    @Test
    fun `a directory with no permission yields no suggestions and does not throw`() =
        runTest {
            val query = MutableStateFlow("")
            val results = mutableListOf<List<ContactSuggestion>>()
            // ContactSuggestions.search swallows SecurityException and returns
            // empty; the feed must simply pass that through.
            val job = launch { contactSuggestionFeed(query, { emptyList() }).toList(results) }

            query.value = "alice"
            advanceTimeBy(SUGGESTION_DEBOUNCE_MS + 1)

            assertThat(results.last()).isEmpty()
            job.cancel()
        }
}
