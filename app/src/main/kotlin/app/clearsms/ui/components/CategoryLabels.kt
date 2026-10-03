package app.clearsms.ui.components

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.clearsms.R
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.InboxPill
import app.clearsms.domain.model.SubCategory

/*
 * The user-visible names of categories and sub-categories.
 *
 * ONE definition per concept: the `labelRes()` mappings below are the only
 * place a category's label lives. Composables read it through
 * `displayName()`, and the few non-composable sites (a snackbar line built
 * from `Resources`) read the same resource through `displayName(resources)`
 * - so the two can never drift, and the Hindi (or any) translation of a
 * label is the string resource's, not a literal's.
 *
 * None of this touches a category's STORED value: the enum NAME is what the
 * database, the rules engine and backups key off (see `Category`), and it is
 * never shown to the user.
 */

/** The string resource naming a primary category - the one place its label lives. */
@StringRes
fun Category.labelRes(): Int =
    when (this) {
        Category.IMPORTANT -> R.string.category_important
        Category.PROMOTIONAL -> R.string.category_promotional
        Category.PERSONAL -> R.string.category_personal
        Category.UNKNOWN -> R.string.category_unknown
        Category.OTP -> R.string.category_otp
        Category.SPAM -> R.string.category_spam
    }

/** Human-readable label for a primary category, in the app's language. */
@Composable
fun Category.displayName(): String = stringResource(labelRes())

/** [displayName] for code outside composition (snackbar text, notifications). */
fun Category.displayName(resources: Resources): String = resources.getString(labelRes())

/** The string resource naming a sub-category tag - the one place its label lives. */
@StringRes
fun SubCategory.labelRes(): Int =
    when (this) {
        SubCategory.TRANSACTION -> R.string.subcategory_transaction
        SubCategory.OTP -> R.string.subcategory_otp
        SubCategory.BILL -> R.string.subcategory_bill
        SubCategory.BANK_ALERT -> R.string.subcategory_bank_alert
        SubCategory.GOVERNMENT -> R.string.subcategory_government
        SubCategory.RECHARGE -> R.string.subcategory_recharge
        SubCategory.INVESTMENT -> R.string.subcategory_investment
        SubCategory.DELIVERY -> R.string.subcategory_delivery
        SubCategory.OFFER -> R.string.subcategory_offer
        SubCategory.SCAM -> R.string.subcategory_scam
        SubCategory.FIXED_DEPOSIT -> R.string.subcategory_fixed_deposit
        SubCategory.MUTUAL_FUND -> R.string.subcategory_mutual_fund
        SubCategory.TRAVEL -> R.string.subcategory_travel
        SubCategory.APPOINTMENT -> R.string.subcategory_appointment
        SubCategory.GENERAL -> R.string.subcategory_general
    }

/** Human-readable label for a sub-category tag, in the app's language. */
@Composable
fun SubCategory.displayName(): String = stringResource(labelRes())

/**
 * Built-in label of an Inbox pill - what it shows until the user renames it
 * in Settings. Every pill reuses its category's label, so a category tag
 * and its pill agree by construction.
 */
@StringRes
fun InboxPill.labelRes(): Int = category.labelRes()

/** [labelRes] resolved in the app's language. */
@Composable
fun InboxPill.defaultLabel(): String = stringResource(labelRes())
