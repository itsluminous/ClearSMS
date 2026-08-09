package app.clearsms.ui.finance

/**
 * Derived display figures for one credit card.
 *
 * A credit card has no "balance" in the savings sense, so the dashboard
 * headline is the issuer-reported AVAILABLE LIMIT. Outstanding is only a
 * derived quantity: total limit minus available limit when both are known,
 * or (legacy fallback) an issuer-reported balance figure when no available
 * limit has been seen. Nothing here ever fabricates a ₹0.
 */
data class CardFigures(
    /** Issuer-reported available credit limit; null until a card SMS carries one. */
    val availableLimit: Double?,
    /**
     * Amount used: total − available when both known AND positive (a derived
     * zero is indistinguishable from a same-figure artifact, so it reads as
     * unknown); legacy issuer-reported balance otherwise (where an explicit
     * 0.0 IS a known, paid-off zero); null when underivable.
     */
    val outstanding: Double?,
    /** 0..1 fraction of the total limit used; null without both figures. */
    val utilization: Float?,
    val level: UtilizationLevel,
)

/** What the card's headline slot shows - exactly one of these, never a fabricated zero. */
sealed interface CardHeadline {
    /** The issuer-reported available limit - the primary, always-preferred figure. */
    data class AvailableLimit(
        val amount: Double,
    ) : CardHeadline

    /** Outstanding only (no available limit known) - legacy issuer-balance data. */
    data class Outstanding(
        val amount: Double,
    ) : CardHeadline

    /** Nothing usable yet: show a neutral "no limit data" state, never ₹0. */
    data object NoData : CardHeadline
}

/** Pure derivation of the credit-card figures so the selection rules are unit-testable. */
object CreditCardFigures {
    /**
     * @param availableLimit issuer-reported available limit ("Avl Limit: INR ...").
     * @param lastKnownBalance legacy issuer-reported balance (treated as outstanding
     *   ONLY when no available limit exists - the semantics differ and must not mix).
     * @param totalLimit the SMS-derived total credit limit (issuer limit-change statements).
     */
    fun compute(
        availableLimit: Double?,
        lastKnownBalance: Double?,
        totalLimit: Double?,
    ): CardFigures {
        val outstanding =
            when {
                // Outstanding = total − available, but ONLY when the result is
                // positive. A derived zero (total ≤ available) is treated as
                // UNKNOWN, not as ₹0: on real data the total and the available
                // limit routinely come from the same figure (a "your new limit
                // is X" statement stores the total while a payment alert stores
                // the same X as available), so their difference carries no
                // spending information - asserting "Outstanding ₹0" there is a
                // lie. A KNOWN zero exists only on the legacy path below, where
                // the issuer explicitly reported the outstanding figure itself.
                availableLimit != null && totalLimit != null && totalLimit > 0.0 ->
                    (totalLimit - availableLimit).takeIf { it > 0.0 }
                // Available limit known but no total: outstanding is underivable.
                availableLimit != null -> null
                // Legacy rows: an issuer-reported balance is the outstanding -
                // a 0.0 here is issuer-asserted (a genuinely paid-off card).
                else -> lastKnownBalance
            }
        val utilization = outstanding?.let { Utilization.fraction(it, totalLimit) }
        return CardFigures(
            availableLimit = availableLimit,
            outstanding = outstanding,
            utilization = utilization,
            level = utilization?.let(Utilization::level) ?: UtilizationLevel.NORMAL,
        )
    }

    /** Headline preference: available limit > legacy outstanding > neutral no-data. */
    fun headline(figures: CardFigures): CardHeadline =
        when {
            figures.availableLimit != null -> CardHeadline.AvailableLimit(figures.availableLimit)
            figures.outstanding != null -> CardHeadline.Outstanding(figures.outstanding)
            else -> CardHeadline.NoData
        }
}
