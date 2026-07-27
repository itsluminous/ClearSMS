package app.clearsms.ui.finance

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Wallet
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clearsms.R
import app.clearsms.data.db.AccountEntity
import app.clearsms.data.db.TransactionEntity
import app.clearsms.domain.model.FinanceTab
import app.clearsms.ui.common.CurrencyFormat
import app.clearsms.ui.common.RelativeTime
import app.clearsms.ui.components.AmountKind
import app.clearsms.ui.components.AmountText
import app.clearsms.ui.components.EmptyState
import app.clearsms.ui.components.SenderAvatar
import app.clearsms.ui.theme.LocalSemanticAmountColors
import kotlinx.coroutines.launch

/** Finance dashboard: monthly net, then one pill-selected section — accounts, credit cards or latest transactions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinanceScreen(
    onOpenAccount: (accountNumber: String, bank: String) -> Unit,
    onOpenMessage: (threadId: Long, messageId: Long) -> Unit,
    viewModel: FinanceViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var limitDialogFor by remember { mutableStateOf<CreditCardItem?>(null) }
    var accountsCollapsed by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val sourceDeletedMessage = stringResource(R.string.source_message_deleted)

    fun openRef(ref: MessageRef?) {
        if (ref != null) {
            onOpenMessage(ref.threadId, ref.messageId)
        } else {
            scope.launch { snackbarHostState.showSnackbar(sourceDeletedMessage) }
        }
    }

    val openTransaction: (TransactionEntity) -> Unit = { tx ->
        scope.launch { openRef(viewModel.sourceMessageFor(tx.rawSmsId)) }
    }
    val openAccountSource: (AccountEntity) -> Unit = { account ->
        scope.launch { openRef(viewModel.sourceMessageForAccount(account)) }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.finance_title)) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        if (state.loaded && state.latestTransactions.isEmpty() && state.bankAccounts.isEmpty() && state.creditCards.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Wallet,
                title = stringResource(R.string.finance_empty_title),
                subtitle = stringResource(R.string.finance_empty_subtitle),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "summary") {
                MonthSummaryCard(net = state.monthNet, debits = state.monthDebits, credits = state.monthCredits)
            }
            item(key = "pills") {
                FinancePillRow(
                    selected = selectedTab,
                    counts = state.pillCounts,
                    onSelect = viewModel::setTab,
                )
            }
            when (selectedTab) {
                FinanceTab.ACCOUNTS ->
                    accountsSection(
                        state = state,
                        collapsed = accountsCollapsed,
                        onToggleCollapsed = { accountsCollapsed = !accountsCollapsed },
                        onOpenAccount = onOpenAccount,
                        onOpenSource = openAccountSource,
                    )
                FinanceTab.CREDIT_CARDS ->
                    creditCardsSection(
                        state = state,
                        onOpenAccount = onOpenAccount,
                        onOpenSource = openAccountSource,
                        onSetLimit = { limitDialogFor = it },
                    )
                FinanceTab.TRANSACTIONS ->
                    transactionsSection(
                        state = state,
                        onOpenTransaction = openTransaction,
                        onLoadMore = viewModel::loadMore,
                    )
            }
        }
    }

    limitDialogFor?.let { card ->
        SetCardLimitDialog(
            card = card,
            onDismiss = { limitDialogFor = null },
            onConfirm = { limit ->
                viewModel.setCardLimit(card.account.id, limit)
                limitDialogFor = null
            },
        )
    }
}

@Composable
private fun FinancePillRow(
    selected: FinanceTab,
    counts: Map<FinanceTab, Int>,
    onSelect: (FinanceTab) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(FinanceTab.entries.toList(), key = { it.name }) { tab ->
            val count = counts[tab] ?: 0
            FilterChip(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                label = { Text(tab.displayName()) },
                trailingIcon =
                    if (count > 0) {
                        { Badge { Text(count.toString()) } }
                    } else {
                        null
                    },
            )
        }
    }
}

@Composable
private fun FinanceTab.displayName(): String =
    when (this) {
        FinanceTab.ACCOUNTS -> stringResource(R.string.finance_accounts)
        FinanceTab.CREDIT_CARDS -> stringResource(R.string.finance_credit_cards)
        FinanceTab.TRANSACTIONS -> stringResource(R.string.finance_latest_transactions)
    }

private fun LazyListScope.accountsSection(
    state: FinanceUiState,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    onOpenAccount: (accountNumber: String, bank: String) -> Unit,
    onOpenSource: (AccountEntity) -> Unit,
) {
    if (state.bankAccounts.isEmpty()) {
        emptySectionItem()
        return
    }
    item(key = "accounts_header") {
        SectionHeader(
            title = stringResource(R.string.finance_accounts),
            collapsed = collapsed,
            onToggle = onToggleCollapsed,
        )
    }
    if (!collapsed) {
        items(state.bankAccounts, key = { "acc_${it.id}" }) { account ->
            ElevatedCard(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(
                            onClickLabel = stringResource(R.string.finance_open_account_detail),
                        ) { onOpenAccount(account.accountNumber, account.bankName) },
            ) {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Icon(Icons.Outlined.AccountBalance, contentDescription = null) },
                    headlineContent = {
                        Text(
                            text = account.bankName.ifBlank { stringResource(R.string.finance_unknown_bank) },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    supportingContent = {
                        Text(
                            text =
                                stringResource(R.string.finance_masked_account, account.accountNumber) + " · " +
                                    stringResource(
                                        R.string.finance_updated,
                                        RelativeTime.format(account.lastUpdated),
                                    ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            account.lastKnownBalance?.let { balance ->
                                AmountText(amount = balance, kind = AmountKind.BALANCE)
                            } ?: Text(
                                text = "—",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            IconButton(onClick = { onOpenSource(account) }) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.OpenInNew,
                                    contentDescription = stringResource(R.string.finance_open_source_sms),
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}

private fun LazyListScope.creditCardsSection(
    state: FinanceUiState,
    onOpenAccount: (accountNumber: String, bank: String) -> Unit,
    onOpenSource: (AccountEntity) -> Unit,
    onSetLimit: (CreditCardItem) -> Unit,
) {
    if (state.creditCards.isEmpty()) {
        emptySectionItem()
        return
    }
    if (state.cardsAboveSafeLimit > 0) {
        item(key = "cards_alert") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = stringResource(R.string.finance_high_usage_alert, state.cardsAboveSafeLimit),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
    items(state.creditCards, key = { "card_${it.account.id}" }) { card ->
        CreditCardCard(
            card = card,
            onOpen = { onOpenAccount(card.account.accountNumber, card.account.bankName) },
            onOpenSource = { onOpenSource(card.account) },
            onSetLimit = { onSetLimit(card) },
        )
    }
}

private fun LazyListScope.transactionsSection(
    state: FinanceUiState,
    onOpenTransaction: (TransactionEntity) -> Unit,
    onLoadMore: () -> Unit,
) {
    if (state.latestTransactions.isEmpty()) {
        emptySectionItem()
        return
    }
    items(state.latestTransactions, key = { "tx_${it.id}" }) { tx ->
        ListItem(
            modifier =
                Modifier.clickable(
                    onClickLabel = stringResource(R.string.finance_open_source_sms),
                ) { onOpenTransaction(tx) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            leadingContent = { SenderAvatar(name = tx.bankName.ifBlank { tx.merchantName ?: "?" }, size = 40.dp) },
            headlineContent = {
                Text(
                    text = tx.merchantName ?: tx.bankName.ifBlank { stringResource(R.string.finance_transaction) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                Text(
                    text =
                        listOfNotNull(tx.bankName.takeIf { it.isNotBlank() }, tx.accountNumber.takeIf { it.isNotBlank() })
                            .joinToString(" · ") + "  " + RelativeTime.format(tx.timestamp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = { AmountText(amount = tx.amount, type = tx.type) },
        )
    }
    item(key = "tx_load_more") {
        LoadMoreRow(
            hasMore = state.hasMoreTransactions,
            loading = state.isLoadingMore,
            onLoadMore = onLoadMore,
        )
    }
}

/** "Load more" control, replaced by a subtle terminator once everything is loaded. */
@Composable
fun LoadMoreRow(
    hasMore: Boolean,
    loading: Boolean,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        if (hasMore) {
            val description = stringResource(R.string.finance_load_more_desc)
            OutlinedButton(
                onClick = onLoadMore,
                enabled = !loading,
                modifier = Modifier.semantics { contentDescription = description },
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.finance_loading_more))
                } else {
                    Text(stringResource(R.string.finance_load_more))
                }
            }
        } else {
            Text(
                text = stringResource(R.string.finance_all_loaded),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun LazyListScope.emptySectionItem() {
    item(key = "section_empty") {
        Text(
            text = stringResource(R.string.finance_section_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 24.dp),
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    collapsed: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onToggle) {
            Icon(
                imageVector = if (collapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                contentDescription =
                    if (collapsed) {
                        stringResource(R.string.finance_expand_section)
                    } else {
                        stringResource(R.string.finance_collapse_section)
                    },
            )
        }
    }
}

@Composable
private fun MonthSummaryCard(
    net: Double,
    debits: Double,
    credits: Double,
) {
    val amountColors = LocalSemanticAmountColors.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.finance_this_month),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            // Fixed semantic color: net outflow red, net inflow green.
            AmountText(
                amount = net,
                kind = if (net < 0) AmountKind.DEBIT else AmountKind.CREDIT,
                style = MaterialTheme.typography.displaySmall,
            )
            Spacer(Modifier.height(8.dp))
            val creditsText = CurrencyFormat.rupees(credits)
            val debitsText = CurrencyFormat.rupees(debits)
            val breakdown = stringResource(R.string.finance_month_breakdown, creditsText, debitsText)
            Text(
                text =
                    buildAnnotatedString {
                        append(breakdown)
                        // Credits arg comes first in the template, debits second;
                        // first/last occurrence keeps this right when both format
                        // to the same string.
                        val creditsAt = breakdown.indexOf(creditsText)
                        if (creditsAt >= 0) {
                            addStyle(SpanStyle(color = amountColors.credit), creditsAt, creditsAt + creditsText.length)
                        }
                        val debitsAt = breakdown.lastIndexOf(debitsText)
                        if (debitsAt >= 0) {
                            addStyle(SpanStyle(color = amountColors.debit), debitsAt, debitsAt + debitsText.length)
                        }
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun CreditCardCard(
    card: CreditCardItem,
    onOpen: () -> Unit,
    onOpenSource: () -> Unit,
    onSetLimit: () -> Unit,
) {
    val barColor =
        when (card.level) {
            UtilizationLevel.NORMAL -> MaterialTheme.colorScheme.primary
            UtilizationLevel.WARNING -> MaterialTheme.colorScheme.tertiary
            UtilizationLevel.DANGER -> MaterialTheme.colorScheme.error
        }
    ElevatedCard(
        modifier =
            Modifier.fillMaxWidth().clickable(
                onClickLabel = stringResource(R.string.finance_open_account_detail),
                onClick = onOpen,
            ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CreditCard, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 6.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = card.account.bankName.ifBlank { stringResource(R.string.finance_unknown_bank) },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.finance_masked_account, card.account.accountNumber),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = stringResource(R.string.finance_outstanding),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AmountText(amount = card.outstanding, kind = AmountKind.BALANCE)
                }
            }
            AnimatedVisibility(visible = card.utilization != null) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { card.utilization ?: 0f },
                        modifier = Modifier.fillMaxWidth(),
                        color = barColor,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text =
                            stringResource(
                                R.string.finance_utilization,
                                ((card.utilization ?: 0f) * 100).toInt(),
                            ),
                        style = MaterialTheme.typography.labelMedium,
                        color = barColor,
                    )
                }
            }
            Row {
                TextButton(onClick = onSetLimit) {
                    Text(stringResource(R.string.finance_set_card_limit))
                }
                TextButton(onClick = onOpenSource) {
                    Text(stringResource(R.string.finance_open_source_sms))
                }
            }
        }
    }
}

@Composable
private fun SetCardLimitDialog(
    card: CreditCardItem,
    onDismiss: () -> Unit,
    onConfirm: (Double?) -> Unit,
) {
    var text by rememberSaveable {
        mutableStateOf(
            card.account.creditLimit
                ?.toString()
                .orEmpty(),
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.finance_set_card_limit)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.finance_limit_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.toDoubleOrNull()) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
