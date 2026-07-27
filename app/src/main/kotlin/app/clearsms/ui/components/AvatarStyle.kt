package app.clearsms.ui.components

import app.clearsms.domain.model.SubCategory

/** How an avatar is rendered for a given sender and settings state. */
enum class AvatarStyle {
    /** The saved contact's photo thumbnail. */
    PHOTO,

    /** A user-supplied image from the optional local logo pack. */
    LOGO,

    /** A logo image bundled in the APK's assets, keyed by curated brand. */
    BUNDLED,

    /** Curated brand tile: brand color + monogram from the bundled brand table. */
    BRAND,

    /** Monogram tile with a category glyph for directory-known senders. */
    BRAND_MARK,

    /** Plain initial-on-tonal-color avatar. */
    PLAIN,
}

/** Small category glyph shown on a [SenderBrandMark]. */
enum class BrandGlyph {
    BANK,
    CARD,
    WALLET,
    CART,
    DELIVERY,
    GOVERNMENT,
    TELECOM,
    UTILITY,
    INVESTMENT,
    HEALTH,
    TRAVEL,
    NONE,
}

/**
 * Picks the avatar rendering. With rich avatars ON the fallback chain is:
 * contact photo → user-supplied logo (always overrides bundled artwork) →
 * bundled asset logo → curated brand tile → category glyph tile
 * (directory-known sender) → plain letter avatar. OFF is always plain —
 * no photos, no logos, no brand colors. Unknown senders always land on the
 * letter avatar, never a blank tile.
 */
fun avatarStyleFor(
    richAvatars: Boolean,
    photoUri: String?,
    isKnownSender: Boolean,
    hasLogo: Boolean = false,
    hasBundledLogo: Boolean = false,
    hasBrand: Boolean = false,
): AvatarStyle =
    when {
        !richAvatars -> AvatarStyle.PLAIN
        photoUri != null -> AvatarStyle.PHOTO
        hasLogo -> AvatarStyle.LOGO
        hasBundledLogo -> AvatarStyle.BUNDLED
        hasBrand -> AvatarStyle.BRAND
        isKnownSender -> AvatarStyle.BRAND_MARK
        else -> AvatarStyle.PLAIN
    }

/** Maps a curated brand's category to its badge glyph. */
fun BrandCategory.toGlyph(): BrandGlyph =
    when (this) {
        BrandCategory.BANK -> BrandGlyph.BANK
        BrandCategory.CARD -> BrandGlyph.CARD
        BrandCategory.WALLET -> BrandGlyph.WALLET
        BrandCategory.TELECOM -> BrandGlyph.TELECOM
        BrandCategory.ECOMMERCE -> BrandGlyph.CART
        BrandCategory.DELIVERY -> BrandGlyph.DELIVERY
        BrandCategory.GOVERNMENT -> BrandGlyph.GOVERNMENT
        BrandCategory.UTILITY -> BrandGlyph.UTILITY
        BrandCategory.INVESTMENT -> BrandGlyph.INVESTMENT
        BrandCategory.HEALTH -> BrandGlyph.HEALTH
        BrandCategory.TRAVEL -> BrandGlyph.TRAVEL
        BrandCategory.OTHER -> BrandGlyph.NONE
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
