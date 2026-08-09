package app.clearsms.ui.finance

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clearsms.R
import app.clearsms.data.db.TransactionEntity
import app.clearsms.ui.common.CurrencyFormat
import app.clearsms.ui.common.RelativeTime
import app.clearsms.ui.components.AmountText
import app.clearsms.ui.components.BalanceMask
import app.clearsms.ui.components.BrandGlyph
import app.clearsms.ui.components.EmptyState
import app.clearsms.ui.components.SenderAvatar
import app.clearsms.ui.theme.LocalSemanticAmountColors
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale

private val MONTH_HEADER = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

/** Account detail: chart, direction filter, monthly groups and expandable rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailScreen(
    onBack: () -> Unit,
    onOpenMessage: (threadId: Long, messageId: Long) -> Unit,
    viewModel: AccountDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var noteDialogFor by remember { mutableStateOf<TransactionEntity?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val sourceDeletedMessage = stringResource(R.string.source_message_deleted)

    val openTransaction: (TransactionEntity) -> Unit = { tx ->
        scope.launch {
            val ref = viewModel.sourceMessageFor(tx.rawSmsId)
            if (ref != null) {
                onOpenMessage(ref.threadId, ref.messageId)
            } else {
                snackbarHostState.showSnackbar(sourceDeletedMessage)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SenderAvatar(
                            name = state.bankName.ifBlank { stringResource(R.string.finance_unknown_bank) },
                            richAvatars = state.showRichAvatars,
                            isKnownSender = state.bankName.isNotBlank(),
                            glyph = BrandGlyph.BANK,
                        )
                        Spacer(Modifier.padding(horizontal = 6.dp))
                        Column {
                            Text(
                                text = state.bankName.ifBlank { stringResource(R.string.finance_unknown_bank) },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            maskedAccountLabel(state.accountNumber)?.let { label ->
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                // No per-screen reveal eye here: the transaction list (individual
                // amounts) is never masked, and the few gated figures follow the
                // app-wide reveal set on the Finance dashboard (shared session
                // state), so a toggle on this screen was redundant and confusing.
            )
        },
    ) { padding ->
        if (state.loaded && state.groups.isEmpty() && state.filter == TxFilter.ALL) {
            EmptyState(
                icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                title = stringResource(R.string.account_empty_title),
                subtitle = stringResource(R.string.account_empty_subtitle),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding =
                androidx.compose.foundation.layout
                    .PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "chart") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(vertical = 12.dp)) {
                        Text(
                            text = stringResource(R.string.account_monthly_chart),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        MonthlyBarChart(data = state.chart)
                    }
                }
            }
            item(key = "filter") {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    TxFilter.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = state.filter == option,
                            onClick = { viewModel.setFilter(option) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = TxFilter.entries.size),
                        ) {
                            Text(
                                text =
                                    when (option) {
                                        TxFilter.ALL -> stringResource(R.string.account_filter_all)
                                        TxFilter.DEBITED -> stringResource(R.string.account_filter_debited)
                                        TxFilter.CREDITED -> stringResource(R.string.account_filter_credited)
                                    },
                            )
                        }
                    }
                }
            }
            state.groups.forEach { group ->
                item(key = "month_${group.month}") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = MONTH_HEADER.format(group.month.atDay(1)),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        val amountColors = LocalSemanticAmountColors.current
                        Text(
                            text = CurrencyFormat.signedRupees(group.credits, positive = true),
                            style = MaterialTheme.typography.labelMedium,
                            color = amountColors.credit,
                        )
                        Text(
                            text = "  " + CurrencyFormat.signedRupees(group.debits, positive = false),
                            style = MaterialTheme.typography.labelMedium,
                            color = amountColors.debit,
                        )
                    }
                }
                items(group.transactions, key = { "tx_${it.id}" }) { tx ->
                    TransactionRow(
                        tx = tx,
                        loadSms = { viewModel.smsBodyFor(tx.rawSmsId) },
                        balanceGated = state.balanceGated,
                        balancesRevealed = state.balancesRevealed,
                        onAddNote = { noteDialogFor = tx },
                        onOpenMessage = { openTransaction(tx) },
                    )
                }
            }
            item(key = "tx_load_more") {
                LoadMoreRow(
                    hasMore = state.hasMoreTransactions,
                    loading = state.isLoadingMore,
                    onLoadMore = viewModel::loadMore,
                )
            }
        }
    }

    noteDialogFor?.let { tx ->
        AddNoteDialog(
            initial = tx.note.orEmpty(),
            onDismiss = { noteDialogFor = null },
            onConfirm = { note ->
                viewModel.addNote(tx.id, note)
                noteDialogFor = null
            },
        )
    }
}

@Composable
private fun TransactionRow(
    tx: TransactionEntity,
    loadSms: suspend () -> String?,
    balanceGated: Boolean,
    balancesRevealed: Boolean,
    onAddNote: () -> Unit,
    onOpenMessage: () -> Unit,
) {
    var expanded by rememberSaveable(tx.id) { mutableStateOf(false) }
    var smsBody by remember(tx.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(expanded) {
        if (expanded && smsBody == null) smsBody = loadSms()
    }

    Card(
        modifier =
            Modifier.fillMaxWidth().clickable(
                onClickLabel =
                    if (expanded) {
                        stringResource(R.string.finance_collapse_section)
                    } else {
                        stringResource(R.string.finance_expand_section)
                    },
            ) { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = tx.merchantName ?: tx.bankName.ifBlank { stringResource(R.string.finance_transaction) },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = RelativeTime.format(tx.timestamp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AmountText(amount = tx.amount, type = tx.type)
            }
            tx.note?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    tx.balance?.let {
                        // "Balance after" is a real account balance, so the
                        // privacy gate masks it like the Finance dashboard;
                        // the transaction amount above stays visible.
                        val masked = BalanceMask.isMasked(balanceGated, balancesRevealed)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val balanceText = if (masked) BalanceMask.MASK else CurrencyFormat.rupees(it)
                            val line = stringResource(R.string.account_balance_after, balanceText)
                            val balanceColor = LocalSemanticAmountColors.current.balance
                            val hiddenDescription = stringResource(R.string.balance_hidden)
                            Text(
                                text =
                                    buildAnnotatedString {
                                        append(line)
                                        if (!masked) {
                                            val at = line.indexOf(balanceText)
                                            if (at >= 0) {
                                                addStyle(SpanStyle(color = balanceColor), at, at + balanceText.length)
                                            }
                                        }
                                    },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier =
                                    if (masked) {
                                        Modifier.clearAndSetSemantics { contentDescription = hiddenDescription }
                                    } else {
                                        Modifier
                                    },
                            )
                        }
                    }
                    tx.referenceNumber?.let {
                        Text(
                            text = stringResource(R.string.account_reference, it),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // The raw SMS body quotes the balance verbatim, so it
                    // stays hidden while balances are masked - otherwise the
                    // gate would be trivially bypassed by expanding a row.
                    if (!BalanceMask.isMasked(balanceGated, balancesRevealed)) {
                        smsBody?.let { body ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = body,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row {
                        TextButton(onClick = onAddNote) {
                            Text(stringResource(R.string.account_add_note))
                        }
                        TextButton(onClick = onOpenMessage) {
                            Text(stringResource(R.string.account_open_message))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddNoteDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_add_note)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.account_note_label)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
