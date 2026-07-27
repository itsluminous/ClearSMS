package app.clearsms.ui.components

import app.clearsms.domain.model.SubCategory

/** How an avatar is rendered for a given sender and settings state. */
enum class AvatarStyle {
    /** The saved contact's photo thumbnail. */
    PHOTO,

    /** Monogram brand mark with a category glyph for known senders. */
    BRAND_MARK,

    /** Today's plain initial-on-tonal-color avatar. */
    PLAIN,
}

/** Small category glyph shown on a [SenderBrandMark]. */
enum class BrandGlyph {
    BANK,
    CART,
    GOVERNMENT,
    TELECOM,
    NONE,
}

/**
 * Picks the avatar rendering: photos and brand marks only when the
 * "rich avatars" setting is on; otherwise always the plain avatar.
 */
fun avatarStyleFor(
    richAvatars: Boolean,
    photoUri: String?,
    isKnownSender: Boolean,
): AvatarStyle =
    when {
        !richAvatars -> AvatarStyle.PLAIN
        photoUri != null -> AvatarStyle.PHOTO
        isKnownSender -> AvatarStyle.BRAND_MARK
        else -> AvatarStyle.PLAIN
    }

/**
 * Derives a category glyph for a known sender from the message sub-category
 * and, failing that, from keywords in the resolved sender name.
 */
fun brandGlyphFor(
    subCategory: SubCategory?,
    senderName: String,
): BrandGlyph {
    when (subCategory) {
        SubCategory.TRANSACTION,
        SubCategory.BANK_ALERT,
        SubCategory.INVESTMENT,
        SubCategory.FIXED_DEPOSIT,
        SubCategory.MUTUAL_FUND,
        -> return BrandGlyph.BANK
        SubCategory.GOVERNMENT -> return BrandGlyph.GOVERNMENT
        SubCategory.RECHARGE -> return BrandGlyph.TELECOM
        SubCategory.DELIVERY, SubCategory.OFFER -> return BrandGlyph.CART
        else -> Unit
    }
    val name = senderName.lowercase()
    return when {
        BANK_NAME_HINTS.any { it in name } -> BrandGlyph.BANK
        TELECOM_NAME_HINTS.any { it in name } -> BrandGlyph.TELECOM
        CART_NAME_HINTS.any { it in name } -> BrandGlyph.CART
        else -> BrandGlyph.NONE
    }
}

private val BANK_NAME_HINTS = listOf("bank", "finance", "credit card")
private val TELECOM_NAME_HINTS = listOf("airtel", "jio", "vodafone", "bsnl", "telecom")
private val CART_NAME_HINTS = listOf("amazon", "flipkart", "myntra", "delivery", "cart")
