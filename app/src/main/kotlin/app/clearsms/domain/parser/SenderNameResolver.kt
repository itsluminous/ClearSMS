package app.clearsms.domain.parser

/**
 * Resolves well-known bank names from sender IDs and message bodies.
 *
 * This is a lightweight fallback used by the transaction parser; full sender
 * resolution goes through the bundled sender ID directory.
 */
object SenderNameResolver {
    private val BANKS =
        listOf(
            "HDFC" to "HDFC Bank",
            "ICICI" to "ICICI Bank",
            "SBI" to "SBI",
            "AXIS" to "Axis Bank",
            "KOTAK" to "Kotak Mahindra Bank",
            "PNB" to "Punjab National Bank",
            "CANARA" to "Canara Bank",
            "UNION" to "Union Bank of India",
            "BOB" to "Bank of Baroda",
            "IDFC" to "IDFC FIRST Bank",
            "INDUSIND" to "IndusInd Bank",
            "FEDERAL" to "Federal Bank",
            "YESBNK" to "Yes Bank",
            "YES BANK" to "Yes Bank",
            "PAYTM" to "Paytm Payments Bank",
        )

    /** Returns a human-readable bank name for [senderId] or [body], if recognizable. */
    fun bankNameFor(
        senderId: String,
        body: String = "",
    ): String? {
        val sender = senderId.uppercase()
        BANKS.firstOrNull { (key, _) -> sender.contains(key) }?.let { return it.second }
        val upperBody = body.uppercase()
        return BANKS.firstOrNull { (key, _) -> upperBody.contains(key) }?.second
    }
}
