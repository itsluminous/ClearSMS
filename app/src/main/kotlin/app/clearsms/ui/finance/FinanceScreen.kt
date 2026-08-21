package app.clearsms.ui.finance

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Wallet
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import app.clearsms.ui.components.BalanceMask
import app.clearsms.ui.components.BalanceRevealButton
import app.clearsms.ui.components.BalanceRevealCardButton
import app.clearsms.ui.components.BrandGlyph
import app.clearsms.ui.components.EmptyState
import app.clearsms.ui.components.MaskedAmountText
import app.clearsms.ui.components.SenderAvatar
import app.clearsms.ui.components.SwipeDismissSnackbarHost
import app.clearsms.ui.navigation.SearchSettingsActions
import app.clearsms.ui.navigation.orderedPills
import app.clearsms.ui.theme.LocalSemanticAmountColors
import kotlinx.coroutines.launch

/** Finance dashboard: monthly net, then one pill-selected section - accounts, credit cards or latest transactions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinanceScreen(
    onOpenAccount: (accountNumber: String, bank: String) -> Unit,
    onOpenMessage: (threadId: Long, messageId: Long) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    viewModel: FinanceViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val summaryExpanded by viewModel.summaryExpanded.collectAsStateWithLifecycle()
    val showOlderAccounts by viewModel.showOlderAccounts.collectAsStateWithLifecycle()
    val showOlderCards by viewModel.showOlderCards.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
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

    // ONE screen-level reveal, now a labelled button INSIDE the summary card
    // (the bare top-bar eye floated in empty app-bar space and was easy to
    // miss). The gate is still global: authenticating once reveals every
    // gated figure on the screen.
    val onToggleBalances =
        balanceToggleHandler(
            revealed = state.balancesRevealed,
            onReveal = viewModel::revealBalances,
            onConceal = viewModel::concealBalances,
        )

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SwipeDismissSnackbarHost(snackbarHostState) },
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.finance_title)) },
                // Same search + settings pair as the Inbox (see SearchSettingsActions).
                actions = { SearchSettingsActions(onSearch = onSearch, onSettings = onSettings) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val hasAccounts =
            state.bankAccounts.isNotEmpty() ||
                state.staleBankAccounts.isNotEmpty() ||
                state.creditCards.isNotEmpty() ||
                state.staleCreditCards.isNotEmpty()
        if (state.loaded && state.latestTransactions.isEmpty() && !hasAccounts) {
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
                MonthSummaryCard(
                    net = state.monthNet,
                    debits = state.monthDebits,
                    credits = state.monthCredits,
                    txCount = state.monthTxCount,
                    debitCount = state.monthDebitCount,
                    creditCount = state.monthCreditCount,
                    excludedCount = state.monthExcludedCount,
                    excludedTotal = state.monthExcludedTotal,
                    expanded = summaryExpanded,
                    onToggle = viewModel::toggleSummaryBreakdown,
                    gated = state.balanceGated,
                    revealed = state.balancesRevealed,
                    onToggleBalances = onToggleBalances,
                )
            }
            item(key = "pills") {
                FinancePillRow(
                    selected = selectedTab,
                    counts = state.pillCounts,
                    pillOrder = state.pillOrder,
                    onSelect = viewModel::setTab,
                )
            }
            when (selectedTab) {
                FinanceTab.ACCOUNTS ->
                    accountsSection(
                        state = state,
                        collapsed = accountsCollapsed,
                        showOlder = showOlderAccounts,
                        onToggleShowOlder = viewModel::toggleShowOlderAccounts,
                        onToggleCollapsed = { accountsCollapsed = !accountsCollapsed },
                        onOpenAccount = onOpenAccount,
                        onOpenSource = openAccountSource,
                    )
                FinanceTab.CREDIT_CARDS ->
                    creditCardsSection(
                        state = state,
                        showOlder = showOlderCards,
                        onToggleShowOlder = viewModel::toggleShowOlderCards,
                        onOpenAccount = onOpenAccount,
                        onOpenSource = openAccountSource,
                    )
                FinanceTab.TRANSACTIONS ->
                    transactionsSection(
                        state = state,
                        onOpenTransaction = openTransaction,
                        onLoadMore = viewModel::loadMore,
                    )
                FinanceTab.RECHARGES ->
                    rechargesSection(
                        state = state,
                        onOpenTransaction = openTransaction,
                    )
            }
        }
    }
}

@Composable
private fun FinancePillRow(
    selected: FinanceTab,
    counts: Map<FinanceTab, Int>,
    pillOrder: List<FinanceTab>,
    onSelect: (FinanceTab) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(orderedPills(pillOrder, FinanceTab.entries.toList()), key = { it.name }) { tab ->
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
internal fun FinanceTab.displayName(): String =
    when (this) {
        FinanceTab.ACCOUNTS -> stringResource(R.string.finance_accounts)
        FinanceTab.CREDIT_CARDS -> stringResource(R.string.finance_credit_cards)
        FinanceTab.TRANSACTIONS -> stringResource(R.string.finance_latest_transactions)
        FinanceTab.RECHARGES -> stringResource(R.string.finance_recharges)
    }

private fun LazyListScope.accountsSection(
    state: FinanceUiState,
    collapsed: Boolean,
    showOlder: Boolean,
    onToggleShowOlder: () -> Unit,
    onToggleCollapsed: () -> Unit,
    onOpenAccount: (accountNumber: String, bank: String) -> Unit,
    onOpenSource: (AccountEntity) -> Unit,
) {
    if (state.bankAccounts.isEmpty() && state.staleBankAccounts.isEmpty()) {
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
            BankAccountCard(
                account = account,
                richAvatars = state.showRichAvatars,
                gated = state.balanceGated,
                revealed = state.balancesRevealed,
                onOpen = { onOpenAccount(account.accountNumber, account.bankName) },
                onOpenSource = { onOpenSource(account) },
            )
        }
        if (state.staleBankAccounts.isNotEmpty()) {
            item(key = "accounts_show_older") {
                ShowOlderRow(
                    count = state.staleBankAccounts.size,
                    expanded = showOlder,
                    onToggle = onToggleShowOlder,
                )
            }
            if (showOlder) {
                items(state.staleBankAccounts, key = { "acc_${it.id}" }) { account ->
                    BankAccountCard(
                        account = account,
                        richAvatars = state.showRichAvatars,
                        gated = state.balanceGated,
                        revealed = state.balancesRevealed,
                        onOpen = { onOpenAccount(account.accountNumber, account.bankName) },
                        onOpenSource = { onOpenSource(account) },
                    )
                }
            }
        }
    }
}

/**
 * One bank account / wallet row - shared by the active and older lists.
 *
 * Layout contract: the trailing amount measures FIRST at its natural width
 * (never squeezed), the name column takes the remaining width and wraps to
 * at most [FinanceRowLayout.MAX_NAME_LINES] lines before ellipsizing. The
 * per-row eye and open-in-new buttons are gone: revealing is screen-level
 * (top-bar eye) and the whole card opens the account detail - the source
 * message stays reachable via long-press and from the detail screen's rows.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BankAccountCard(
    account: AccountEntity,
    richAvatars: Boolean,
    gated: Boolean,
    revealed: Boolean,
    onOpen: () -> Unit,
    onOpenSource: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .combinedClickable(
                        onClickLabel = stringResource(R.string.finance_open_account_detail),
                        onLongClickLabel = stringResource(R.string.finance_open_source_sms),
                        onClick = onOpen,
                        onLongClick = onOpenSource,
                    ).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SenderAvatar(
                name = account.bankName.ifBlank { stringResource(R.string.finance_unknown_bank) },
                richAvatars = richAvatars,
                isKnownSender = account.bankName.isNotBlank(),
                glyph = accountGlyph(account.type),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = account.bankName.ifBlank { stringResource(R.string.finance_unknown_bank) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = FinanceRowLayout.MAX_NAME_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        listOfNotNull(
                            maskedAccountLabel(account.accountNumber),
                            stringResource(
                                R.string.finance_updated,
                                RelativeTime.format(account.lastUpdated),
                            ),
                        ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            account.lastKnownBalance?.let { balance ->
                MaskedAmountText(
                    amount = balance,
                    kind = AmountKind.BALANCE,
                    gated = gated,
                    revealed = revealed,
                )
            } ?: Text(
                text = "-",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * Disclosure row for accounts/cards with no update in over a year
 * ([StaleAccounts.STALE_AFTER]). Collapsed by default; expansion is
 * session-only.
 */
@Composable
private fun ShowOlderRow(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text =
                if (expanded) {
                    stringResource(R.string.finance_hide_older)
                } else {
                    stringResource(R.string.finance_show_older, count)
                },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun LazyListScope.creditCardsSection(
    state: FinanceUiState,
    showOlder: Boolean,
    onToggleShowOlder: () -> Unit,
    onOpenAccount: (accountNumber: String, bank: String) -> Unit,
    onOpenSource: (AccountEntity) -> Unit,
) {
    if (state.creditCards.isEmpty() && state.staleCreditCards.isEmpty()) {
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
            richAvatars = state.showRichAvatars,
            gated = state.balanceGated,
            revealed = state.balancesRevealed,
            onOpen = { onOpenAccount(card.account.accountNumber, card.account.bankName) },
            onOpenSource = { onOpenSource(card.account) },
        )
    }
    if (state.staleCreditCards.isNotEmpty()) {
        item(key = "cards_show_older") {
            ShowOlderRow(
                count = state.staleCreditCards.size,
                expanded = showOlder,
                onToggle = onToggleShowOlder,
            )
        }
        if (showOlder) {
            items(state.staleCreditCards, key = { "card_${it.account.id}" }) { card ->
                CreditCardCard(
                    card = card,
                    richAvatars = state.showRichAvatars,
                    gated = state.balanceGated,
                    revealed = state.balancesRevealed,
                    onOpen = { onOpenAccount(card.account.accountNumber, card.account.bankName) },
                    onOpenSource = { onOpenSource(card.account) },
                )
            }
        }
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
        TransactionRow(tx = tx, showRichAvatars = state.showRichAvatars, onOpenTransaction = onOpenTransaction)
    }
    item(key = "tx_load_more") {
        LoadMoreRow(
            hasMore = state.hasMoreTransactions,
            loading = state.isLoadingMore,
            onLoadMore = onLoadMore,
        )
    }
}

/** Prepaid recharges only - same row rendering as the transactions pill. */
private fun LazyListScope.rechargesSection(
    state: FinanceUiState,
    onOpenTransaction: (TransactionEntity) -> Unit,
) {
    if (state.rechargeTransactions.isEmpty()) {
        emptySectionItem()
        return
    }
    items(state.rechargeTransactions, key = { "rc_${it.id}" }) { tx ->
        TransactionRow(tx = tx, showRichAvatars = state.showRichAvatars, onOpenTransaction = onOpenTransaction)
    }
}

@Composable
private fun TransactionRow(
    tx: TransactionEntity,
    showRichAvatars: Boolean,
    onOpenTransaction: (TransactionEntity) -> Unit,
) {
    ListItem(
        modifier =
            Modifier.clickable(
                onClickLabel = stringResource(R.string.finance_open_source_sms),
            ) { onOpenTransaction(tx) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        leadingContent = {
            SenderAvatar(
                name = financeTransactionAvatarName(tx.merchantName, tx.bankName),
                richAvatars = showRichAvatars,
                isKnownSender = tx.bankName.isNotBlank(),
                glyph = BrandGlyph.BANK,
            )
        },
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

/**
 * The big monthly figure, now interactive: tapping expands an inline
 * breakdown (money in / money out with counts) in place. Expansion was chosen
 * over navigating to the transactions list because the list has no
 * month-scoped filter - jumping there would show *latest* transactions, not
 * "this month" - and expanding in place leaves the persisted pill selection
 * untouched, so the banner never fights the pill row's own state.
 *
 * The totals exclude self-transfers and credit-card bill payments (see
 * [MonthSummary]); the expanded breakdown says so, with the excluded sum.
 *
 * When balances are gated this card also hosts the labelled reveal button
 * ([BalanceRevealCardButton]) right under the masked figure - the button
 * consumes its own taps, so the card's expand tap and chevron are unaffected.
 */
@Composable
private fun MonthSummaryCard(
    net: Double,
    debits: Double,
    credits: Double,
    txCount: Int,
    debitCount: Int,
    creditCount: Int,
    excludedCount: Int,
    excludedTotal: Double,
    expanded: Boolean,
    onToggle: () -> Unit,
    gated: Boolean,
    revealed: Boolean,
    onToggleBalances: () -> Unit,
) {
    val amountColors = LocalSemanticAmountColors.current
    // The month net is a balance-like aggregate, so the privacy gate masks
    // it (and its in/out breakdown) along with account balances. Transaction
    // counts stay visible - they carry no amounts.
    val masked = BalanceMask.isMasked(gated, revealed)
    val creditsText = if (masked) BalanceMask.MASK else CurrencyFormat.rupees(credits)
    val debitsText = if (masked) BalanceMask.MASK else CurrencyFormat.rupees(debits)
    val valueDescription =
        if (masked) {
            stringResource(R.string.balance_hidden)
        } else {
            stringResource(
                R.string.finance_summary_value_desc,
                CurrencyFormat.rupees(net),
                creditsText,
                debitsText,
                txCount,
            )
        }
    val clickLabel =
        stringResource(
            if (expanded) R.string.finance_summary_hide_breakdown else R.string.finance_summary_show_breakdown,
        )
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // The clickable sits inside the Card so the ripple is clipped to the
        // card shape; heightIn guards the 48dp touch target even when empty.
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = clickLabel, onClick = onToggle)
                .semantics { contentDescription = valueDescription }
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.finance_this_month),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (masked) {
                Text(
                    text = BalanceMask.MASK,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                // Fixed semantic color: net outflow red, net inflow green.
                AmountText(
                    amount = net,
                    kind = if (net < 0) AmountKind.DEBIT else AmountKind.CREDIT,
                    style = MaterialTheme.typography.displaySmall,
                )
            }
            BalanceRevealCardButton(
                state = BalanceRevealButton.state(gated = gated, revealed = revealed),
                onToggle = onToggleBalances,
            )
            Spacer(Modifier.height(8.dp))
            if (!expanded) {
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
            AnimatedVisibility(visible = expanded) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
                    Spacer(Modifier.height(8.dp))
                    SummaryBreakdownRow(
                        label = stringResource(R.string.finance_breakdown_received, creditCount),
                        amountText = creditsText,
                        color = amountColors.credit,
                    )
                    SummaryBreakdownRow(
                        label = stringResource(R.string.finance_breakdown_spent, debitCount),
                        amountText = debitsText,
                        color = amountColors.debit,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.finance_summary_tx_count, txCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryBreakdownRow(
    label: String,
    amountText: String,
    color: Color,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = amountText,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

/**
 * One credit card. The headline figure is the issuer-reported AVAILABLE
 * LIMIT (see [CreditCardFigures.headline]) - never a fabricated ₹0 balance.
 * When both the total limit and the available limit are known, outstanding
 * (total − available) is shown as a secondary line and drives the
 * utilization bar. Tap opens the account detail; long-press opens the
 * source message (the open-source button was removed with the per-row eye).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CreditCardCard(
    card: CreditCardItem,
    richAvatars: Boolean,
    gated: Boolean,
    revealed: Boolean,
    onOpen: () -> Unit,
    onOpenSource: () -> Unit,
) {
    val figures = card.figures
    val barColor =
        when (figures.level) {
            UtilizationLevel.NORMAL -> MaterialTheme.colorScheme.primary
            UtilizationLevel.WARNING -> MaterialTheme.colorScheme.tertiary
            UtilizationLevel.DANGER -> MaterialTheme.colorScheme.error
        }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClickLabel = stringResource(R.string.finance_open_account_detail),
                    onLongClickLabel = stringResource(R.string.finance_open_source_sms),
                    onClick = onOpen,
                    onLongClick = onOpenSource,
                ).padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SenderAvatar(
                    name = card.account.bankName.ifBlank { stringResource(R.string.finance_unknown_bank) },
                    richAvatars = richAvatars,
                    isKnownSender = card.account.bankName.isNotBlank(),
                    glyph = accountGlyph(card.account.type),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = card.account.bankName.ifBlank { stringResource(R.string.finance_unknown_bank) },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = FinanceRowLayout.MAX_NAME_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                    maskedAccountLabel(card.account.accountNumber)?.let { label ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    when (val headline = CreditCardFigures.headline(figures)) {
                        is CardHeadline.AvailableLimit -> {
                            Text(
                                text = stringResource(R.string.finance_available_limit),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            MaskedAmountText(
                                amount = headline.amount,
                                kind = AmountKind.BALANCE,
                                gated = gated,
                                revealed = revealed,
                            )
                        }
                        is CardHeadline.Outstanding -> {
                            Text(
                                text = stringResource(R.string.finance_outstanding),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            MaskedAmountText(
                                amount = headline.amount,
                                kind = AmountKind.BALANCE,
                                gated = gated,
                                revealed = revealed,
                            )
                        }
                        CardHeadline.NoData ->
                            Text(
                                text = stringResource(R.string.finance_no_limit_data),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                    }
                }
            }
            // Outstanding = total − available, only when derivable AND not
            // already the headline (i.e. the available limit is known).
            if (figures.availableLimit != null && figures.outstanding != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.finance_outstanding),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    MaskedAmountText(
                        amount = figures.outstanding,
                        kind = AmountKind.BALANCE,
                        gated = gated,
                        revealed = revealed,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            AnimatedVisibility(visible = figures.utilization != null) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { figures.utilization ?: 0f },
                        modifier = Modifier.fillMaxWidth(),
                        color = barColor,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text =
                            stringResource(
                                R.string.finance_utilization,
                                ((figures.utilization ?: 0f) * 100).toInt(),
                            ),
                        style = MaterialTheme.typography.labelMedium,
                        color = barColor,
                    )
                }
            }
        }
    }
}
