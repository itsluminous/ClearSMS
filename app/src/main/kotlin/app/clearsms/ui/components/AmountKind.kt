package app.clearsms.ui.components

/**
 * Semantic meaning of a rendered amount, mapped to a fixed color
 * ([app.clearsms.ui.theme.SemanticAmountColors] — never the dynamic
 * Material palette).
 */
enum class AmountKind {
    /** Money left an account — always red. */
    DEBIT,

    /** Money arrived — always green. */
    CREDIT,

    /** A balance report with no movement — always blue. */
    BALANCE,
}

/**
 * Derives the [AmountKind] from a message's parsed extraction map
 * ([app.clearsms.data.db.MessageEntity.extractedDataJson] decoded): an
 * explicit `type` wins; a parsed `balance` with no transaction type is a
 * balance-only message; anything else has no semantic amount.
 */
fun amountKindOf(details: Map<String, String>): AmountKind? =
    when (details["type"]?.lowercase()) {
        "debit" -> AmountKind.DEBIT
        "credit" -> AmountKind.CREDIT
        else -> if ("balance" in details) AmountKind.BALANCE else null
    }
