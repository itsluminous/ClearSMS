package app.clearsms.ui.composemsg

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map

/**
 * ONE definition of contact autocomplete behaviour, shared by every surface
 * that offers it (the compose screen's recipient field and Settings' block
 * list). Keeping the debounce and the search call in one place is what stops
 * the two fields from drifting into different feels.
 *
 * A blank query yields no suggestions, so an empty field never shows a list;
 * [search] itself fails soft to an empty list when READ_CONTACTS is missing,
 * which is why no permission handling appears here. Whatever [search]
 * returns is passed through [dedupeSuggestions]: the lists this feed drives
 * are keyed per row, and a contact stored twice must never become two rows
 * with one key (that is a hard crash in a lazy list).
 */
@OptIn(FlowPreview::class)
internal fun contactSuggestionFeed(
    query: Flow<String>,
    search: suspend (String) -> List<ContactSuggestion>,
    debounceMs: Long = SUGGESTION_DEBOUNCE_MS,
): Flow<List<ContactSuggestion>> =
    query
        .debounce(debounceMs)
        .map { text -> if (text.isBlank()) emptyList() else dedupeSuggestions(search(text)) }

/** Typing pause before the contacts provider is queried. */
internal const val SUGGESTION_DEBOUNCE_MS = 200L
