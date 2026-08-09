package app.clearsms.ui.finance

/**
 * Layout contract for Finance list rows (accounts, credit cards).
 *
 * The trailing amount always measures at its natural width first; the name
 * column takes the remaining width, wraps to at most [MAX_NAME_LINES]
 * lines, then ellipsizes. This is what lets "State Bank of India" degrade
 * predictably at large font scales instead of wrapping onto three lines
 * against a wall of per-row icon buttons.
 */
object FinanceRowLayout {
    /** Names get up to two lines before ellipsizing - never three. */
    const val MAX_NAME_LINES = 2
}
