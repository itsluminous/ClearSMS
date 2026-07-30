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
        /**
         * Whether this institution can OWN an account/card. Banks and wallets
         * are issuers; payment channels (CRED), ecommerce brands (Flipkart)
         * and telecoms (Airtel) are not — they appear in money messages as
         * merchants or conduits, never as the account's home.
         */
        val isIssuer: Boolean = true,
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
            // CRED is a payment CHANNEL for other banks' credit cards — a
            // "payment received for card 4001" via CRED belongs to whichever
            // bank issued card 4001, so CRED must never own an account.
            Institution("CRED", listOf("CREDCL", "CREDIN"), listOf("CRED"), isIssuer = false),
            Institution("PayZapp", listOf("PAYZAP"), listOf("PAYZAPP")),
            // Sodexo meal cards were rebranded to Pluxee; both ids are one wallet.
            Institution("Pluxee", listOf("PLUXEE", "SODEXO"), listOf("PLUXEE", "SODEXO")),
            // Protean (formerly NSDL e-Gov) is the NPS Central Recordkeeping
            // Agency: an NPS contribution creates a real investment account
            // (identified by the PRAN tail), so Protean IS an issuer — the
            // user's NPS holdings deserve an account card like any deposit.
            // Displayed as "Protean NPS" so the account card says what it
            // holds; the bare "PROTEAN" alias keeps older stored names
            // canonicalizing to the same account.
            Institution("Protean NPS", listOf("PTNNPS", "PROTEA", "NSDLNP", "NSDLPN", "CRANPS"), listOf("PROTEAN NPS", "PROTEAN")),
            // EPFO passbook contributions are deposits into a real
            // provident-fund account (keyed on the member-id tail), so like
            // Protean/NPS the EPFO IS an issuer and gets an account card.
            Institution("EPFO", listOf("EPFOHO"), listOf("EPFO")),
            // Non-bank issuers seen in real inboxes — better display names for
            // the sender-ID fallback path. Not issuers: a Flipkart refund goes
            // TO a bank account, an Airtel bill is charged FROM one.
            Institution("Flipkart", listOf("FLPKRT"), listOf("FLIPKART"), isIssuer = false),
            Institution("Airtel", listOf("AIRTEL", "AIRBIL", "AIRINF"), listOf("AIRTEL"), isIssuer = false),
            // The other telecoms, so their recharge and bill rows are titled by
            // brand rather than falling back to a generic phrase.
            Institution("Jio", listOf("JIOSMS", "RJIOSM", "JIOFBR", "JIOBB", "JIO"), listOf("RELIANCE JIO", "JIO"), isIssuer = false),
            Institution("Vi", listOf("VICARE", "VIDEA", "VODAFO", "IDEAMN"), listOf("VODAFONE IDEA", "VODAFONE"), isIssuer = false),
            Institution("BSNL", listOf("BSNLMB", "BSNLOF", "BSNL"), listOf("BSNL"), isIssuer = false),
            // Sony LIV subscription confirmations arrive from LIVCNF; the
            // friendly name keeps the Subscriptions view readable. An OTT
            // service is never an account's home, so it is not an issuer.
            Institution("Sony LIV", listOf("LIVCNF", "SONYLV"), listOf("SONY LIV", "SONYLIV"), isIssuer = false),
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
     * The sender's KNOWN brand name, or null when nothing recognises it.
     *
     * Same lookup as [bankNameFor] minus its last-resort fallback to the raw
     * sender id: callers that use the result as a user-visible title (a recharge
     * or bill payment where the biller is the counterparty) must not end up
     * showing "RCHRGE".
     */
    fun brandNameFor(
        senderId: String,
        body: String = "",
    ): String? {
        matchBodyInstitution(body)?.let { return it.name }
        val normalized = normalizeSender(senderId)
        INSTITUTIONS
            .firstOrNull { inst -> inst.senderKeys.any { normalized.contains(it) } }
            ?.let { return it.name }
        return matchAlias(normalized)?.name
    }

    /**
     * The single account-creation guardrail: whether [name] can plausibly
     * OWN an account or card — i.e. is a financial institution or wallet.
     *
     * Rationale: three real misattribution shapes all shared one root cause —
     * an `AccountEntity` was created from whatever name landed in
     * `bankName`, even when that name was a merchant ("at Paytm"), a payment
     * channel (CRED forwarding a card payment), or an ecommerce brand
     * (a Flipkart refund credited to a bank account). An account row must
     * only ever be created for a plausible issuer:
     *  - a curated institution whose kind is bank/wallet ([Institution.isIssuer]),
     *  - or an uncurated name that self-evidently names a bank ("...BANK..."),
     *  - or a name the [body] explicitly places in a card/account phrase
     *    ("linked to your <Name> Card", "<Name> A/c") — an unknown-but-real
     *    issuer named by its own message.
     * Everything else (merchant names, payment apps, ecommerce brands, raw
     * shortcodes) must NOT create an account; the caller keeps the account's
     * bank blank so a later, properly attributed message can claim it.
     */
    fun isPlausibleIssuer(
        name: String?,
        body: String = "",
    ): Boolean {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        val upper = trimmed.uppercase()
        matchAlias(upper)?.let { return it.isIssuer }
        if (upper.contains("BANK")) return true
        if (body.isNotEmpty()) {
            val anchored =
                Regex(
                    "(?i)(?<![A-Za-z0-9])${Regex.escape(trimmed)}\\s+(?:bank|card|a/c|acct|account|wallet)\\b",
                )
            if (anchored.containsMatchIn(body)) return true
        }
        return false
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
