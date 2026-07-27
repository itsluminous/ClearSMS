package app.clearsms.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clearsms.R
import app.clearsms.domain.model.Category
import app.clearsms.ui.common.RelativeTime
import app.clearsms.ui.components.CategoryBadge
import app.clearsms.ui.components.EmptyState
import app.clearsms.ui.components.SenderAvatar
import app.clearsms.ui.components.displayName

/** Full-text search with category and date filters and highlighted matches. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onOpenThread: (threadId: Long, messageId: Long) -> Unit,
    onBack: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::onQueryChange,
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                        singleLine = true,
                        trailingIcon = {
                            if (state.query.isNotEmpty()) {
                                IconButton(onClick = { viewModel.onQueryChange("") }) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = stringResource(R.string.search_clear),
                                    )
                                }
                            }
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item(key = "category_filters") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(Category.entries.toList(), key = { it.name }) { cat ->
                        FilterChip(
                            selected = state.category == cat,
                            onClick = { viewModel.toggleCategory(cat) },
                            label = { Text(cat.displayName()) },
                        )
                    }
                }
            }
            item(key = "date_filters") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(DateFilter.entries.toList(), key = { it.name }) { date ->
                        FilterChip(
                            selected = state.dateFilter == date,
                            onClick = { viewModel.setDateFilter(date) },
                            label = {
                                Text(
                                    when (date) {
                                        DateFilter.ANY -> stringResource(R.string.search_date_any)
                                        DateFilter.LAST_7_DAYS -> stringResource(R.string.search_date_7d)
                                        DateFilter.LAST_30_DAYS -> stringResource(R.string.search_date_30d)
                                        DateFilter.LAST_YEAR -> stringResource(R.string.search_date_year)
                                    },
                                )
                            },
                        )
                    }
                }
            }
            if (state.searched && state.results.isEmpty()) {
                item(key = "no_results") {
                    EmptyState(
                        icon = Icons.Outlined.SearchOff,
                        title = stringResource(R.string.search_no_results_title),
                        subtitle = stringResource(R.string.search_no_results_subtitle),
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    )
                }
            }
            items(state.results, key = { it.message.id }) { item ->
                val message = item.message
                ListItem(
                    modifier = Modifier.clickable { onOpenThread(message.threadId, message.id) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    leadingContent = {
                        SenderAvatar(
                            name = item.display.name,
                            size = 40.dp,
                            richAvatars = state.richAvatars,
                            photoUri = item.display.photoUri,
                            isKnownSender = item.display.isKnownSender,
                            glyph = item.glyph,
                        )
                    },
                    headlineContent = {
                        Text(
                            text = highlight(item.display.name, state.query),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = {
                        Text(
                            text = highlight(message.body, state.query),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        Text(
                            text = RelativeTime.format(message.timestamp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    overlineContent = { CategoryBadge(category = message.category) },
                )
            }
        }
    }
}

/** Bolds and tints all case-insensitive occurrences of [query] within [text]. */
@Composable
private fun highlight(
    text: String,
    query: String,
): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)
    val highlightStyle =
        SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    return buildAnnotatedString {
        var start = 0
        while (start < text.length) {
            val index = text.indexOf(query, startIndex = start, ignoreCase = true)
            if (index < 0) {
                append(text.substring(start))
                break
            }
            append(text.substring(start, index))
            pushStyle(highlightStyle)
            append(text.substring(index, index + query.length))
            pop()
            start = index + query.length
        }
    }
}
