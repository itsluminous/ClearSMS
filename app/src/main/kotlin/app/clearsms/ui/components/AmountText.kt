package app.clearsms.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import app.clearsms.domain.model.CurrencyCatalog
import app.clearsms.domain.model.MoneyFormat
import app.clearsms.domain.model.TransactionType
import app.clearsms.ui.theme.ClearSmsTheme
import app.clearsms.ui.theme.LocalSemanticAmountColors

/**
 * Amount rendered in its fixed semantic color - red for debits, green for
 * credits, blue for balance-only amounts - from
 * [app.clearsms.ui.theme.SemanticAmountColors], deliberately NOT the
 * Material `colorScheme` roles (which shift with the wallpaper on
 * Android 12+). Balances carry no +/− sign because no money moved. The
 * text comes from [MoneyFormat] driven by the row's stored [currency] -
 * `₹1,23,456` for rupees, `$1.000` for Chilean pesos, `US$40.95` for a
 * foreign card spend - never a hardcoded rupee sign.
 */
@Composable
fun AmountText(
    amount: Double,
    /** ISO 4217 code the amount is denominated in - the stored currency, never assumed. */
    currency: String,
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
            AmountKind.DEBIT -> MoneyFormat.signed(amount, positive = false, currencyCode = currency)
            AmountKind.CREDIT -> MoneyFormat.signed(amount, positive = true, currencyCode = currency)
            AmountKind.BALANCE -> MoneyFormat.format(amount, currency)
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
    currency: String,
    type: TransactionType,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
) {
    AmountText(
        amount = amount,
        currency = currency,
        kind = if (type == TransactionType.DEBIT) AmountKind.DEBIT else AmountKind.CREDIT,
        modifier = modifier,
        style = style,
    )
}

@Preview
@Composable
private fun AmountTextPreview() {
    ClearSmsTheme {
        AmountText(amount = 1234.5, currency = CurrencyCatalog.INR_CODE, kind = AmountKind.BALANCE)
    }
}
