package app.clearsms.ui.conversation

/**
 * Whether the parsed extraction card (amount, merchant, account, balance…)
 * renders under an expanded bubble. This is what Settings → Appearance →
 * "Show extracted message details" controls: a verbosity toggle for the
 * derived card only - the raw message text is always shown, and the Finance
 * balance-privacy gate is a separate setting.
 */
object DetailCardVisibility {
    fun shouldShow(
        details: Map<String, String>,
        showTransactionDetails: Boolean,
    ): Boolean = showTransactionDetails && details.isNotEmpty()
}
