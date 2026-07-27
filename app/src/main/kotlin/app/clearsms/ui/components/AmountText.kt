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

/** Amount rendered in semantic color: error for debits, tertiary for credits. */
@Composable
fun AmountText(
    amount: Double,
    type: TransactionType,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
) {
    val debit = type == TransactionType.DEBIT
    Text(
        text = CurrencyFormat.signedRupees(amount, positive = !debit),
        style = style,
        fontWeight = FontWeight.SemiBold,
        color = if (debit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
        modifier = modifier,
    )
}

@Preview
@Composable
private fun AmountTextPreview() {
    ClearSmsTheme {
        AmountText(amount = 1234.5, type = TransactionType.DEBIT)
    }
}
