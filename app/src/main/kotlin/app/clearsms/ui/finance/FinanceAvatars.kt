package app.clearsms.ui.finance

import app.clearsms.domain.model.AccountType
import app.clearsms.domain.model.ReminderType
import app.clearsms.ui.components.BrandGlyph

/**
 * Which name a transaction row's avatar (and brand resolution) uses: the
 * merchant where known, otherwise the bank — never blank.
 */
fun financeTransactionAvatarName(
    merchantName: String?,
    bankName: String,
): String = merchantName?.takeIf { it.isNotBlank() } ?: bankName.ifBlank { "?" }

/** Category glyph for an account's avatar tile. */
fun accountGlyph(type: AccountType): BrandGlyph =
    when (type) {
        AccountType.CREDIT_CARD -> BrandGlyph.CARD
        AccountType.WALLET -> BrandGlyph.WALLET
        AccountType.SAVINGS -> BrandGlyph.BANK
    }

/** Category glyph for a reminder card's institution avatar. */
fun reminderGlyph(type: ReminderType): BrandGlyph =
    when (type) {
        ReminderType.CREDIT_CARD -> BrandGlyph.CARD
        ReminderType.EMI, ReminderType.DEPOSIT -> BrandGlyph.BANK
        ReminderType.INSURANCE -> BrandGlyph.HEALTH
        ReminderType.DELIVERY -> BrandGlyph.DELIVERY
        ReminderType.SUBSCRIPTION, ReminderType.OTHER -> BrandGlyph.NONE
    }
