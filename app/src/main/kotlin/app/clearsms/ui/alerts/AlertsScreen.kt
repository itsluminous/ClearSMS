package app.clearsms.ui.alerts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clearsms.R
import app.clearsms.data.db.ReminderEntity
import app.clearsms.domain.model.ReminderType
import app.clearsms.ui.common.CurrencyFormat
import app.clearsms.ui.components.EmptyState
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DUE_DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/** Alerts: upcoming bill/payment reminder cards plus a collapsible past section. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(
    onOpenMessage: (threadId: Long, messageId: Long) -> Unit,
    viewModel: AlertsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val sourceDeletedMessage = stringResource(R.string.source_message_deleted)

    val openReminder: (ReminderEntity) -> Unit = { reminder ->
        scope.launch {
            val ref = viewModel.sourceMessageFor(reminder.rawSmsId)
            if (ref != null) {
                onOpenMessage(ref.threadId, ref.messageId)
            } else {
                snackbarHostState.showSnackbar(sourceDeletedMessage)
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.alerts_title)) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        if (state.loaded && state.upcoming.isEmpty() && state.past.isEmpty() && state.filter == AlertFilter.ALL) {
            EmptyState(
                icon = Icons.Outlined.NotificationsNone,
                title = stringResource(R.string.alerts_empty_title),
                subtitle = stringResource(R.string.alerts_empty_subtitle),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item(key = "chips") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(AlertFilter.entries.toList(), key = { it.name }) { option ->
                        FilterChip(
                            selected = state.filter == option,
                            onClick = { viewModel.setFilter(option) },
                            label = {
                                Text(
                                    when (option) {
                                        AlertFilter.ALL -> stringResource(R.string.alerts_filter_all)
                                        AlertFilter.CREDIT_CARDS -> stringResource(R.string.alerts_filter_credit_cards)
                                        AlertFilter.EMI -> stringResource(R.string.alerts_filter_emi)
                                        AlertFilter.OTHERS -> stringResource(R.string.alerts_filter_others)
                                    },
                                )
                            },
                        )
                    }
                }
            }
            items(state.upcoming, key = { "up_${it.id}" }) { reminder ->
                ReminderCard(
                    reminder = reminder,
                    onOpen = { openReminder(reminder) },
                    onDismiss = { viewModel.dismiss(reminder.id) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            if (state.past.isNotEmpty()) {
                item(key = "past_header") {
                    PastRemindersHeader(
                        count = state.past.size,
                        expanded = state.pastExpanded,
                        onToggle = viewModel::togglePastExpanded,
                    )
                }
                if (state.pastExpanded) {
                    items(state.past, key = { "past_${it.id}" }) { reminder ->
                        ReminderCard(
                            reminder = reminder,
                            onOpen = { openReminder(reminder) },
                            onDismiss = { viewModel.dismiss(reminder.id) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            past = true,
                        )
                    }
                }
            }
        }
    }
}

/** Collapsible section header: count in the title and a rotation-animated chevron. */
@Composable
private fun PastRemindersHeader(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val rotation by animateFloatAsState(targetValue = if (expanded) 180f else 0f, label = "past_chevron")
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(
                    onClickLabel =
                        if (expanded) {
                            stringResource(R.string.alerts_collapse_past)
                        } else {
                            stringResource(R.string.alerts_expand_past)
                        },
                    onClick = onToggle,
                ).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.alerts_past_reminders_count, count),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Outlined.ExpandMore,
            contentDescription =
                if (expanded) {
                    stringResource(R.string.alerts_collapse_past)
                } else {
                    stringResource(R.string.alerts_expand_past)
                },
            modifier = Modifier.rotate(rotation),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReminderCard(
    reminder: ReminderEntity,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    past: Boolean = false,
) {
    ElevatedCard(
        modifier =
            modifier.fillMaxWidth().clickable(
                onClickLabel = stringResource(R.string.finance_open_source_sms),
                onClick = onOpen,
            ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeBadge(type = reminder.type)
                Spacer(Modifier.weight(1f))
                reminder.bankName?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            reminder.dueDate?.let { dueMs ->
                Text(
                    text =
                        stringResource(
                            R.string.alerts_due_on,
                            DUE_DATE_FORMAT.format(Instant.ofEpochMilli(dueMs).atZone(ZoneId.systemDefault())),
                        ),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (past) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            reminder.accountLast4?.let {
                Text(
                    text = stringResource(R.string.finance_masked_account, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                reminder.totalDue?.let {
                    Column {
                        Text(
                            text = stringResource(R.string.alerts_total_due),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = CurrencyFormat.rupees(it),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                reminder.minDue?.let {
                    Column {
                        Text(
                            text = stringResource(R.string.alerts_min_due),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = CurrencyFormat.rupees(it),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.alerts_dismiss))
            }
        }
    }
}

@Composable
private fun TypeBadge(type: ReminderType) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text =
                when (type) {
                    ReminderType.CREDIT_CARD -> stringResource(R.string.alerts_type_credit_card)
                    ReminderType.EMI -> stringResource(R.string.alerts_type_emi)
                    ReminderType.INSURANCE -> stringResource(R.string.alerts_type_insurance)
                    ReminderType.SUBSCRIPTION -> stringResource(R.string.alerts_type_subscription)
                    ReminderType.OTHER -> stringResource(R.string.alerts_type_other)
                },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
