package app.clearsms.domain.model

/**
 * Primary message categories shown as inbox tabs.
 *
 * "Unread" is a filter applied on top of these categories, not a category itself.
 */
enum class Category {
    IMPORTANT,
    PROMOTIONAL,

    /**
     * INTERNAL-ONLY legacy value — never shown to the user.
     *
     * It used to be a visible pill for notices that require no action and move
     * no money (broker/exchange statements, flight/train PNR and boarding info,
     * appointment tokens, credit-score access notices, UPI-mandate lifecycle
     * notices). Those messages are now classified as [IMPORTANT] (the
     * categorizer folds this value away; sub-categories are preserved).
     *
     * The entry is retained rather than deleted because message rows persisted
     * by earlier versions store the literal string "INFORMATIONAL", and the
     * Room converter parses categories with [Category.valueOf] — removing the
     * constant would crash every upgrade that still has such rows. Rules may
     * also still declare `category: informational`; the rule engine keeps
     * mapping that here and the categorizer normalizes the result. Use
     * [userFacing] before displaying any stored category.
     */
    INFORMATIONAL,
    PERSONAL,
    UNKNOWN,
    OTP,
    ;

    companion object {
        /**
         * Categories the user can see and filter by (pills, badges, search
         * chips), in declaration order. Excludes internal-only values.
         */
        val userVisible: List<Category> = entries.filterNot { it == INFORMATIONAL }
    }
}

/**
 * Folds internal-only values into the category the user actually sees:
 * [Category.INFORMATIONAL] (legacy stored rows) reads as [Category.IMPORTANT].
 */
fun Category.userFacing(): Category = if (this == Category.INFORMATIONAL) Category.IMPORTANT else this
