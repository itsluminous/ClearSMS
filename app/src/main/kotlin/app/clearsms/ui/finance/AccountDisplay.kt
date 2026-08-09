package app.clearsms.ui.finance

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.clearsms.R

/**
 * "xx1234" for a real masked tail; null for an issuer-keyed card account.
 *
 * A curated card product (Scapia Federal) never quotes account digits in
 * its SMS, so its account row carries a stable synthetic key instead of a
 * last-4 (see the ingestion-side account-identity rules). That key is an
 * internal identifier: rendering it as "xxSCAPIAFEDERAL" would present
 * noise as a card number, so such accounts show no masked-number line -
 * the issuer name already identifies the card.
 */
@Composable
fun maskedAccountLabel(accountNumber: String): String? =
    if (accountNumber.any { it.isDigit() }) {
        stringResource(R.string.finance_masked_account, accountNumber)
    } else {
        null
    }
