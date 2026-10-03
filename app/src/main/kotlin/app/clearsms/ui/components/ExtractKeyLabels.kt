package app.clearsms.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.clearsms.R

/*
 * The user-visible names of the keys a rule extracts ("amount", "due_date",
 * "account_last4" …) and of the enum-ish VALUES some of them carry.
 *
 * ONE definition per concept: this is the only place an extract key's label
 * lives. The parsed-detail card under a bubble and the rule wizard's
 * field selector both read it, so the same key can never be spelt two ways.
 *
 * None of this touches the key itself: it is what the rule JSON, the
 * `extractedDataJson` column and backups store, and it is never rewritten.
 * The key set is open (a user's own rule may extract anything), so an
 * unknown key is shown exactly as its author typed it - there is no
 * resource to translate it with, and dressing it up as an English phrase
 * (`replace('_', ' ')`) is no more Hindi than the raw key.
 */

/**
 * The string resource naming a well-known extract key - every key the
 * ingestion pipeline writes and every key the bundled rules extract - or
 * null for a key only a user's own rule knows about.
 */
@StringRes
fun extractKeyLabelRes(key: String): Int? =
    when (key) {
        "amount" -> R.string.extract_key_amount
        "type" -> R.string.extract_key_type
        "account_last4" -> R.string.extract_key_account_last4
        "bank" -> R.string.extract_key_bank
        "merchant" -> R.string.extract_key_merchant
        "balance" -> R.string.extract_key_balance
        "available_limit" -> R.string.extract_key_available_limit
        "total_limit" -> R.string.extract_key_total_limit
        "reference" -> R.string.extract_key_reference
        "currency" -> R.string.extract_key_currency
        "requested_amount" -> R.string.extract_key_requested_amount
        "otp_code" -> R.string.extract_key_otp_code
        "due_date" -> R.string.extract_key_due_date
        "total_due" -> R.string.extract_key_total_due
        "min_due" -> R.string.extract_key_min_due
        "label" -> R.string.extract_key_label
        "operator" -> R.string.extract_key_operator
        "data_remaining" -> R.string.extract_key_data_remaining
        "pnr" -> R.string.extract_key_pnr
        "train" -> R.string.extract_key_train
        "flight" -> R.string.extract_key_flight
        "route" -> R.string.extract_key_route
        "journey_date" -> R.string.extract_key_journey_date
        "departure_time" -> R.string.extract_key_departure_time
        "courier" -> R.string.extract_key_courier
        "tracking_id" -> R.string.extract_key_tracking_id
        else -> null
    }

/** Human-readable label for an extract key, in the app's language; an unknown key is shown as written. */
@Composable
fun extractKeyLabel(key: String): String = extractKeyLabelRes(key)?.let { stringResource(it) } ?: key

/**
 * The label for the VALUE under an extract key when that value is an
 * enum-ish token rather than text lifted from the message: today only the
 * transaction `type`, whose stored values are `debit` / `credit` (see
 * `RuleEngine.ExtractType.TRANSACTION_TYPE`). Null means "free text - show
 * it untouched": a bank or merchant name, a reference, an OTP.
 */
@StringRes
fun extractValueLabelRes(
    key: String,
    value: String,
): Int? =
    when (key) {
        "type" ->
            when (value.lowercase()) {
                "debit" -> R.string.transaction_type_debit
                "credit" -> R.string.transaction_type_credit
                else -> null
            }
        else -> null
    }

/** [extractValueLabelRes] resolved in the app's language, else the value as stored. */
@Composable
fun extractValueLabel(
    key: String,
    value: String,
): String = extractValueLabelRes(key, value)?.let { stringResource(it) } ?: value
