package app.clearsms.ui.finance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import app.clearsms.R
import app.clearsms.data.db.TransactionEntity
import app.clearsms.ui.common.CurrencyFormat
import app.clearsms.ui.components.BalanceMask
import app.clearsms.ui.theme.LocalSemanticAmountColors

/**
 * Pure state/content helpers behind [TransactionExpansionDetails], kept
 * separate for JVM tests. Every finance transaction list (the per-account
 * screen and the Finance tab's Transactions/Recharges pills) routes its
 * inline expansion through this one component so the lists cannot drift.
 */
object TransactionExpansion {
    /**
     * One-expanded-row-at-a-time toggle: tapping the expanded row collapses
     * it, tapping any other row moves the single expansion there.
     */
    fun toggle(
        expandedId: Long?,
        tappedId: Long,
    ): Long? = if (expandedId == tappedId) null else tappedId

    /**
     * What the expansion renders. The full SMS [body] is ALWAYS part of the
     * content - independent of how many fields the parser extracted and of
     * the balance privacy gate. The body used to hide behind the balance
     * mask, which left sparse parses expanding to little more than a "Ref:"
     * line and forced a trip through "Open message" just to read the SMS.
     * Reading the message in place is the point of the expansion, so only
     * the PARSED balance figure stays maskable ([balanceMasked]); the raw
     * body is shown verbatim - a deliberate product decision.
     */
    data class Content(
        val balance: Double?,
        val balanceMasked: Boolean,
        val referenceNumber: String?,
        val body: String?,
    )

    fun content(
        tx: TransactionEntity,
        smsBody: String?,
        balanceMasked: Boolean,
    ): Content =
        Content(
            balance = tx.balance,
            balanceMasked = balanceMasked,
            referenceNumber = tx.referenceNumber,
            body = smsBody,
        )
}

/**
 * The inline expansion of a finance transaction row: parsed detail
 * (balance-after, reference), then the FULL source SMS text, then the row's
 * actions. Bodies can be long - the text wraps freely and the hosting lazy
 * list scrolls, so nothing is truncated.
 *
 * [loadSms] resolves lazily so collapsed rows never touch the database;
 * [onAddNote] is optional because only the per-account list edits notes.
 */
@Composable
fun TransactionExpansionDetails(
    tx: TransactionEntity,
    loadSms: suspend () -> String?,
    balanceGated: Boolean,
    balancesRevealed: Boolean,
    onOpenMessage: () -> Unit,
    onAddNote: (() -> Unit)? = null,
) {
    var smsBody by remember(tx.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(tx.id) {
        if (smsBody == null) smsBody = loadSms()
    }
    val content =
        TransactionExpansion.content(
            tx = tx,
            smsBody = smsBody,
            balanceMasked = BalanceMask.isMasked(balanceGated, balancesRevealed),
        )
    Column {
        Spacer(Modifier.height(8.dp))
        content.balance?.let { balance ->
            // "Balance after" is a real account balance, so the privacy gate
            // masks it like the Finance dashboard; the transaction amount in
            // the row header stays visible.
            Row(verticalAlignment = Alignment.CenterVertically) {
                val balanceText =
                    if (content.balanceMasked) BalanceMask.MASK else CurrencyFormat.rupees(balance)
                val line = stringResource(R.string.account_balance_after, balanceText)
                val balanceColor = LocalSemanticAmountColors.current.balance
                val hiddenDescription = stringResource(R.string.balance_hidden)
                Text(
                    text =
                        buildAnnotatedString {
                            append(line)
                            if (!content.balanceMasked) {
                                val at = line.indexOf(balanceText)
                                if (at >= 0) {
                                    addStyle(SpanStyle(color = balanceColor), at, at + balanceText.length)
                                }
                            }
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        if (content.balanceMasked) {
                            Modifier.clearAndSetSemantics { contentDescription = hiddenDescription }
                        } else {
                            Modifier
                        },
                )
            }
        }
        content.referenceNumber?.let {
            Text(
                text = stringResource(R.string.account_reference, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        content.body?.let { body ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row {
            onAddNote?.let { addNote ->
                TextButton(onClick = addNote) {
                    Text(stringResource(R.string.account_add_note))
                }
            }
            TextButton(onClick = onOpenMessage) {
                Text(stringResource(R.string.account_open_message))
            }
        }
    }
}
