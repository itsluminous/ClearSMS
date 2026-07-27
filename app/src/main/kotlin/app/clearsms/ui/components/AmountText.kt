package app.clearsms.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import app.clearsms.domain.model.TransactionType
import app.clearsms.ui.common.CurrencyFormat
import app.clearsms.ui.theme.ClearSmsTheme

/**
 * Amount rendered in semantic color: error (red) for debits, tertiary (green)
 * for credits, primary (blue) for balance-only amounts. Balances carry no
 * +/− sign because no money moved.
 */
@Composable
fun AmountText(
    amount: Double,
    kind: AmountKind,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
) {
    val color =
        when (kind) {
            AmountKind.DEBIT -> MaterialTheme.colorScheme.error
            AmountKind.CREDIT -> MaterialTheme.colorScheme.tertiary
            AmountKind.BALANCE -> MaterialTheme.colorScheme.primary
        }
    val text =
        when (kind) {
            AmountKind.DEBIT -> CurrencyFormat.signedRupees(amount, positive = false)
            AmountKind.CREDIT -> CurrencyFormat.signedRupees(amount, positive = true)
            AmountKind.BALANCE -> CurrencyFormat.rupees(amount)
        }
    Text(
        text = text,
        style = style,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = modifier,
    )
}

/** Convenience overload for callers holding a [TransactionType]. */
@Composable
fun AmountText(
    amount: Double,
    type: TransactionType,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
) {
    AmountText(
        amount = amount,
        kind = if (type == TransactionType.DEBIT) AmountKind.DEBIT else AmountKind.CREDIT,
        modifier = modifier,
        style = style,
    )
}

@Preview
@Composable
private fun AmountTextPreview() {
    ClearSmsTheme {
        AmountText(amount = 1234.5, kind = AmountKind.BALANCE)
    }
}
