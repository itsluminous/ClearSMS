package app.clearsms.domain.parser

/**
 * Resolves and canonicalizes financial-institution names from sender IDs and
 * message bodies.
 *
 * Resolution chain (first hit wins):
 * 1. an institution named in the BODY (that is the account's bank even when
 *    the SMS arrives via an aggregator like a card-payment app),
 * 2. the sender ID matched against the curated institution table below
 *    (kept in sync with `rules/brands/brands.json` by a unit test),
 * 3. the normalized sender ID itself — an account should never be nameless;
 *    showing "VD-Pluxee" as "PLUXEE" beats showing "Unknown bank".
 *
 * Every returned name is CANONICAL: "SBI" and "State Bank of India" resolve
 * to the same string, so the same institution never produces two account
 * cards. [canonicalize] applies the same mapping to already-stored names
 * (used on write and when merging/migrating existing rows).
 */
object SenderNameResolver {
    private class Institution(
        val name: String,
        /** Sender-ID fragments after TRAI normalization ("VM-HDFCBK" -> "HDFCBK"). */
        val senderKeys: List<String>,
        /** Whole-word aliases matched against bodies and stored names. */
        val aliases: List<String>,
    )

    private val INSTITUTIONS =
        listOf(
            Institution("HDFC Bank", listOf("HDFCBK", "HDFCB"), listOf("HDFC BANK", "HDFC")),
            Institution("ICICI Bank", listOf("ICICIB", "ICICIT"), listOf("ICICI BANK", "ICICI")),
            Institution(
                "State Bank of India",
                listOf("SBIINB", "SBIUPI", "SBIPSG", "SBIOTP", "CBSSBI", "ATMSBI", "SBICRD", "SBIBNK", "SBICAR", "SBIYON"),
                listOf("STATE BANK OF INDIA", "STATE BANK", "SBI CARD", "SBI"),
            ),
            Institution("Axis Bank", listOf("AXISBK", "AXISB"), listOf("AXIS BANK", "AXIS")),
            Institution("Kotak Mahindra Bank", listOf("KOTAKB", "KOTAKM"), listOf("KOTAK MAHINDRA BANK", "KOTAK")),
            Institution("Punjab National Bank", listOf("PNBSMS", "PNBOTP"), listOf("PUNJAB NATIONAL BANK", "PNB")),
            Institution("Bank of Baroda", listOf("BOBTXN", "BOBSMS", "BOBCRD"), listOf("BANK OF BARODA", "BOB CARD")),
            Institution("Canara Bank", listOf("CANBNK", "CANARA"), listOf("CANARA BANK", "CANARA")),
            Institution("Union Bank of India", listOf("UNIONB", "UBOI"), listOf("UNION BANK OF INDIA", "UNION BANK")),
            Institution("IDFC FIRST Bank", listOf("IDFCFB", "IDFCBK"), listOf("IDFC FIRST BANK", "IDFC FIRST", "IDFC")),
            Institution("IndusInd Bank", listOf("INDUSB", "INDBNK"), listOf("INDUSIND BANK", "INDUSIND")),
            Institution("Yes Bank", listOf("YESBNK"), listOf("YES BANK", "YESBANK")),
            Institution("Federal Bank", listOf("FEDBNK", "FEDERL"), listOf("FEDERAL BANK")),
            Institution("Citi", listOf("CITIBK", "CITIBA", "CITI"), listOf("CITI BANK", "CITIBANK", "CITI")),
            Institution(
                "Paytm Payments Bank",
                listOf("PYTMPB", "PAYTMB", "IPAYTM", "PAYTM"),
                listOf("PAYTM PAYMENTS BANK", "PAYTM"),
            ),
            Institution("PhonePe", listOf("PHONPE"), listOf("PHONEPE")),
            Institution("Amazon Pay", listOf("AMZNPY", "APAYIN"), listOf("AMAZON PAY")),
            Institution("CRED", listOf("CREDCL", "CREDIN"), listOf("CRED")),
            Institution("PayZapp", listOf("PAYZAP"), listOf("PAYZAPP")),
            // Sodexo meal cards were rebranded to Pluxee; both ids are one wallet.
            Institution("Pluxee", listOf("PLUXEE", "SODEXO"), listOf("PLUXEE", "SODEXO")),
            // Non-bank issuers seen in real inboxes — better display names for
            // the sender-ID fallback path.
            Institution("Flipkart", listOf("FLPKRT"), listOf("FLIPKART")),
            Institution("Airtel", listOf("AIRTEL", "AIRBIL", "AIRINF"), listOf("AIRTEL")),
        )

    /** Aliases sorted longest-first so "Paytm Payments Bank" wins over "Paytm". */
    private val aliasIndex: List<Pair<Regex, Institution>> =
        INSTITUTIONS
            .flatMap { inst -> inst.aliases.map { alias -> alias to inst } }
            .sortedByDescending { (alias, _) -> alias.length }
            .map { (alias, inst) ->
                Regex("(?<![A-Z0-9])${Regex.escape(alias)}(?![A-Z0-9])") to inst
            }

    /**
     * Human-readable canonical institution name for [senderId]/[body];
     * null only when nothing matches AND the sender is blank.
     */
    fun bankNameFor(
        senderId: String,
        body: String = "",
    ): String? {
        // 1. Institution named in the body — the account's bank even when the
        //    SMS comes from an aggregator (card-payment apps, wallets).
        matchBodyInstitution(body)?.let { return it.name }
        // 2. The sender ID against the curated table.
        val normalized = normalizeSender(senderId)
        INSTITUTIONS
            .firstOrNull { inst -> inst.senderKeys.any { normalized.contains(it) } }
            ?.let { return it.name }
        matchAlias(normalized)?.let { return it.name }
        // 3. Last resort: the normalized sender ID itself.
        return normalized.takeIf { it.isNotBlank() }
    }

    /**
     * An institution named in the body counts only in an ACCOUNT context —
     * "your Axis Bank credit card", "HDFC Bank A/c", "Pluxee ... wallet" —
     * so a merchant/VPA mention ("paid to paytm@upi") never re-labels the
     * account. Aliases containing "BANK" are self-evidently account context.
     */
    private fun matchBodyInstitution(body: String): Institution? {
        if (body.isEmpty()) return null
        val upper = body.uppercase()
        for ((regex, institution) in aliasIndex) {
            val match = regex.find(upper) ?: continue
            if (regex.pattern.contains("BANK")) return institution
            val trailing = upper.substring(match.range.last + 1, minOf(upper.length, match.range.last + 1 + 32))
            if (ACCOUNT_CONTEXT_REGEX.containsMatchIn(trailing)) return institution
        }
        return null
    }

    /**
     * Maps a stored institution name (possibly a variant like "SBI" or
     * "Citi Bank") to its canonical form; unknown names pass through
     * trimmed, blank collapses to null.
     */
    fun canonicalize(name: String?): String? {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        matchAlias(trimmed.uppercase())?.let { return it.name }
        return trimmed
    }

    private fun matchAlias(upperText: String): Institution? {
        if (upperText.isEmpty()) return null
        return aliasIndex.firstOrNull { (regex, _) -> regex.containsMatchIn(upperText) }?.second
    }

    /** Words after a body alias that mark it as the ACCOUNT's institution. */
    private val ACCOUNT_CONTEXT_REGEX =
        Regex("\\b(?:BANK|CARD|A/C|AC|ACCT|ACCOUNT|WALLET|RD|FD|POLICY|CREDIT|DEBIT)\\b")

    /**
     * Strips the TRAI route prefix ("VM-", "AD-", ...) and route suffix
     * ("-S", "-T", "-P", "-G") from an alphanumeric sender and uppercases,
     * mirroring the sender-ID directory's normalization.
     */
    fun normalizeSender(sender: String): String {
        var s = sender.trim().uppercase()
        if (s.length > 3 && s[2] == '-' && s.take(2).all { it.isLetterOrDigit() }) {
            s = s.substring(3)
        }
        if (s.length > 2 && s[s.length - 2] == '-' && s.last() in "STPG") {
            s = s.dropLast(2)
        }
        return s
    }
}
