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
import app.clearsms.ui.theme.LocalSemanticAmountColors

/**
 * Amount rendered in its fixed semantic color - red for debits, green for
 * credits, blue for balance-only amounts - from
 * [app.clearsms.ui.theme.SemanticAmountColors], deliberately NOT the
 * Material `colorScheme` roles (which shift with the wallpaper on
 * Android 12+). Balances carry no +/− sign because no money moved.
 */
@Composable
fun AmountText(
    amount: Double,
    kind: AmountKind,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
) {
    val colors = LocalSemanticAmountColors.current
    val color =
        when (kind) {
            AmountKind.DEBIT -> colors.debit
            AmountKind.CREDIT -> colors.credit
            AmountKind.BALANCE -> colors.balance
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
