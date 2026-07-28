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
 * Detail-map keys written only for bill/payment reminders (due date, total
 * due, minimum due). Their presence means the amount is informational — a
 * bill to be paid, not money that moved — so it must never render as a red
 * debit.
 */
private val BILL_MARKER_KEYS = setOf("due_date", "total_due", "min_due")

/** True when the extraction map describes a bill/payment reminder. */
fun isBillDetails(details: Map<String, String>): Boolean = BILL_MARKER_KEYS.any { it in details }

/**
 * Derives the [AmountKind] from a message's parsed extraction map
 * ([app.clearsms.data.db.MessageEntity.extractedDataJson] decoded).
 *
 * Bill/reminder-derived amounts win first: a bill is informational (nothing
 * moved yet), so it always renders in the blue [AmountKind.BALANCE]
 * treatment with no sign — even if a parser also stamped a debit `type` on
 * the same message. Otherwise an explicit `type` wins; a parsed `balance`
 * with no transaction type is a balance-only message; anything else has no
 * semantic amount.
 */
fun amountKindOf(details: Map<String, String>): AmountKind? =
    when {
        isBillDetails(details) -> AmountKind.BALANCE
        details["type"]?.lowercase() == "debit" -> AmountKind.DEBIT
        details["type"]?.lowercase() == "credit" -> AmountKind.CREDIT
        "balance" in details -> AmountKind.BALANCE
        else -> null
    }
