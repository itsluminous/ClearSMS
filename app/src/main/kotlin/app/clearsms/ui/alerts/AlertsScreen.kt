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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Badge
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clearsms.R
import app.clearsms.data.db.ReminderEntity
import app.clearsms.domain.model.ReminderType
import app.clearsms.ui.common.CurrencyFormat
import app.clearsms.ui.components.AvatarDefaults
import app.clearsms.ui.components.EmptyState
import app.clearsms.ui.components.SenderAvatar
import app.clearsms.ui.finance.reminderGlyph
import app.clearsms.ui.navigation.SearchSettingsActions
import app.clearsms.ui.navigation.orderedPills
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
    onSearch: () -> Unit,
    onSettings: () -> Unit,
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
                // Same search + settings pair as the Inbox (see SearchSettingsActions).
                actions = { SearchSettingsActions(onSearch = onSearch, onSettings = onSettings) },
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
                    items(orderedPills(state.pillOrder, AlertFilter.entries.toList()), key = { it.name }) { option ->
                        val label = option.displayName()
                        val count = state.counts[option] ?: 0
                        val description =
                            pluralStringResource(R.plurals.alerts_filter_pill_reminders, count, label, count)
                        FilterChip(
                            selected = state.filter == option,
                            onClick = { viewModel.setFilter(option) },
                            label = { Text(label) },
                            trailingIcon =
                                if (count > 0) {
                                    { Badge { Text(count.toString()) } }
                                } else {
                                    null
                                },
                            modifier =
                                Modifier
                                    // No explicit height: M3 chips are 32dp tall and already
                                    // expand their touch target to the 48dp minimum, so forcing
                                    // a taller height here made this row inconsistent with the
                                    // Inbox and Finance chip rows.
                                    .semantics { contentDescription = description },
                        )
                    }
                }
            }
            items(state.upcoming, key = { "up_${it.id}" }) { reminder ->
                ReminderCard(
                    reminder = reminder,
                    richAvatars = state.showRichAvatars,
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
                            richAvatars = state.showRichAvatars,
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
    richAvatars: Boolean,
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
                reminder.bankName?.takeIf { it.isNotBlank() }?.let { bank ->
                    Text(
                        text = bank,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    // Same avatar chain as the inbox: bundled logo → brand
                    // tile → category glyph → letter, gated by the setting -
                    // at the compact diameter so the logo sits level with
                    // the card's label text instead of dominating the row.
                    SenderAvatar(
                        name = bank,
                        richAvatars = richAvatars,
                        isKnownSender = true,
                        glyph = reminderGlyph(reminder.type),
                        size = AvatarDefaults.compactSize,
                    )
                }
            }
            val delivery = reminder.type == ReminderType.DELIVERY
            // What the reminder is for: extracted description, or for
            // deliveries the tracking reference.
            val description =
                if (delivery) {
                    reminder.label?.let { stringResource(R.string.alerts_tracking_ref, it) }
                } else {
                    reminder.label
                }
            description?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!delivery) {
                reminder.accountLast4?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.finance_masked_account, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Amount due: total prominent, minimum secondary when present.
                if (reminder.totalDue != null || reminder.minDue != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        reminder.totalDue?.let {
                            Column {
                                Text(
                                    text = stringResource(R.string.alerts_total_due),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = CurrencyFormat.rupees(it),
                                    style = MaterialTheme.typography.headlineSmall,
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
                }
            }
            reminder.dueDate?.let { dueMs ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text =
                        stringResource(
                            if (delivery) R.string.alerts_expected_on else R.string.alerts_due_on,
                            DUE_DATE_FORMAT.format(Instant.ofEpochMilli(dueMs).atZone(ZoneId.systemDefault())),
                        ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (past) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.alerts_dismiss))
            }
        }
    }
}

@Composable
internal fun AlertFilter.displayName(): String =
    when (this) {
        AlertFilter.ALL -> stringResource(R.string.alerts_filter_all)
        AlertFilter.CREDIT_CARDS -> stringResource(R.string.alerts_filter_credit_cards)
        AlertFilter.EMI -> stringResource(R.string.alerts_filter_emi)
        AlertFilter.INSURANCE -> stringResource(R.string.alerts_filter_insurance)
        AlertFilter.BILL -> stringResource(R.string.alerts_filter_bill)
        AlertFilter.SUBSCRIPTION -> stringResource(R.string.alerts_filter_subscription)
        AlertFilter.DEPOSIT -> stringResource(R.string.alerts_filter_deposit)
        AlertFilter.DELIVERY -> stringResource(R.string.alerts_filter_delivery)
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
                    ReminderType.DEPOSIT -> stringResource(R.string.alerts_type_deposit)
                    ReminderType.INSURANCE -> stringResource(R.string.alerts_type_insurance)
                    ReminderType.SUBSCRIPTION -> stringResource(R.string.alerts_type_subscription)
                    ReminderType.DELIVERY -> stringResource(R.string.alerts_type_delivery)
                    ReminderType.OTHER -> stringResource(R.string.alerts_type_other)
                },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
