package app.clearsms.domain.parser

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.logging.Logger

/**
 * Resolves and canonicalizes financial-institution names from sender IDs and
 * message bodies.
 *
 * Resolution chain (first hit wins):
 * 1. an institution the BODY names as the ACCOUNT'S OWN bank - next to the
 *    account or card ("in HDFC Bank A/c xx8709", "your Axis Bank credit
 *    card"). This is the account's bank even when the SMS arrives via an
 *    aggregator like a card-payment app. A bank named in remittance
 *    NARRATION ("For IMPS -Federal bank- 616715401395", "NEFT-<bank>-",
 *    "via <bank>") is the COUNTERPARTY's bank and never counts,
 * 2. the sender ID matched against the institution table generated from
 *    `rules/brands/brands.json` (entries carrying an `is_issuer` field),
 * 3. a bank the body merely MENTIONS outside any account context (a
 *    "- Federal Bank" signature) - weaker than the sender, so an HDFC
 *    sender naming another bank in passing stays HDFC,
 * 4. the normalized sender ID itself - an account should never be nameless;
 *    showing "VD-Pluxee" as "PLUXEE" beats showing "Unknown bank".
 *
 * Every returned name is CANONICAL: "SBI" and "State Bank of India" resolve
 * to the same string, so the same institution never produces two account
 * cards. [canonicalize] applies the same mapping to already-stored names
 * (used on write and when merging/migrating existing rows).
 */
object SenderNameResolver {
    internal class Institution(
        val name: String,
        /** Sender-ID fragments after TRAI normalization ("VM-HDFCBK" -> "HDFCBK"). */
        val senderKeys: List<String>,
        /** Whole-word aliases matched against bodies and stored names. */
        val aliases: List<String>,
        /**
         * Whether this institution can OWN an account/card. Banks and wallets
         * are issuers; payment channels (CRED), ecommerce brands (Flipkart)
         * and telecoms (Airtel) are not - they appear in money messages as
         * merchants or conduits, never as the account's home.
         */
        val isIssuer: Boolean = true,
        /**
         * Whether the issuer is a standalone CARD product (brands.json
         * `category == "CARD"`): a co-branded card like Scapia Federal whose
         * transaction SMS may legitimately carry no account digits at all.
         * Only such issuers may own an issuer-keyed (digit-less) card
         * account - see the account-identity rules at the ingestion site.
         */
        val isCardProduct: Boolean = false,
        /**
         * Whether the issuer is a WALLET product (brands.json
         * `category == "WALLET"`): a meal/benefits wallet like Pluxee whose
         * credit/spend SMS may carry no account digits at all. Only such
         * issuers may own an issuer-keyed (digit-less) wallet account -
         * the wallet counterpart of [isCardProduct].
         */
        val isWalletProduct: Boolean = false,
        /**
         * Whether the issuer is a RETIREMENT scheme (brands.json
         * `category == "INVESTMENT"` with `is_issuer`): NPS as reported by
         * either CRA (Protean, KFintech). Some of its money messages carry
         * no PRAN digits ("credited to your NPS Tier-I a/c") - a tail-less
         * valuation may UPDATE the scheme's sole existing account, but
         * unlike cards/wallets it never CREATES one: the PRAN-tailed
         * shapes own the account's identity.
         */
        val isRetirementProduct: Boolean = false,
    )

    /**
     * The institution table, generated from the single source of truth,
     * `rules/brands/brands.json` (bundled as a classpath resource): every
     * brand entry carrying an `is_issuer` field is an institution. The
     * optional `issuer_name` / `issuer_senders` / `issuer_aliases` fields
     * override the brand's display values where the RESOLVER's view differs
     * from the avatar view (e.g. SBI Card messages belong to the State Bank
     * of India account, but keep their own avatar).
     *
     * A malformed or missing table degrades to an empty list with a logged
     * warning - resolution then falls back to normalized sender ids, never
     * a crash.
     */
    private val INSTITUTIONS: List<Institution> by lazy {
        parseInstitutions(readBrandsResource())
    }

    internal fun readBrandsResource(): String? =
        try {
            SenderNameResolver::class.java.classLoader
                ?.getResourceAsStream("brands.json")
                ?.bufferedReader()
                ?.use { it.readText() }
                .also { if (it == null) warnUnusable("resource not found") }
        } catch (e: Exception) {
            warnUnusable(e.toString())
            null
        }

    /** Institutions from a brands.json document; empty (with a warning) when unusable. */
    internal fun parseInstitutions(json: String?): List<Institution> {
        if (json == null) return emptyList()
        return try {
            FORMAT
                .decodeFromString<BrandTableJson>(json)
                .brands
                .mapNotNull { brand ->
                    val isIssuer = brand.isIssuer ?: return@mapNotNull null
                    Institution(
                        name = brand.issuerName ?: brand.name,
                        senderKeys = brand.issuerSenders ?: brand.senders,
                        aliases = brand.issuerAliases ?: brand.aliases,
                        isIssuer = isIssuer,
                        isCardProduct = brand.category == "CARD",
                        isWalletProduct = brand.category == "WALLET",
                        isRetirementProduct = brand.category == "INVESTMENT",
                    )
                }
        } catch (e: Exception) {
            warnUnusable(e.toString())
            emptyList()
        }
    }

    private fun warnUnusable(reason: String) {
        Logger
            .getLogger("ClearSMS")
            .warning("Bundled brands.json unusable ($reason); institution resolution degrades to sender ids")
    }

    private val FORMAT = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class BrandTableJson(
        val brands: List<BrandJson> = emptyList(),
    )

    @Serializable
    private data class BrandJson(
        val name: String,
        val category: String? = null,
        val senders: List<String> = emptyList(),
        val aliases: List<String> = emptyList(),
        @SerialName("is_issuer") val isIssuer: Boolean? = null,
        @SerialName("issuer_name") val issuerName: String? = null,
        @SerialName("issuer_senders") val issuerSenders: List<String>? = null,
        @SerialName("issuer_aliases") val issuerAliases: List<String>? = null,
    )

    /** Aliases sorted longest-first so "Paytm Payments Bank" wins over "Paytm". */
    private val aliasIndex: List<Pair<Regex, Institution>> by lazy {
        INSTITUTIONS
            .flatMap { inst -> inst.aliases.map { alias -> alias to inst } }
            .sortedByDescending { (alias, _) -> alias.length }
            .map { (alias, inst) ->
                Regex("(?<![A-Z0-9])${Regex.escape(alias)}(?![A-Z0-9])") to inst
            }
    }

    /**
     * Human-readable canonical institution name for [senderId]/[body];
     * null only when nothing matches AND the sender is blank.
     */
    fun bankNameFor(
        senderId: String,
        body: String = "",
    ): String? {
        val bodyMatch = matchBodyInstitution(body)
        // 1. The bank the body names as the ACCOUNT'S OWN - even when the
        //    SMS comes from an aggregator (card-payment apps, wallets).
        bodyMatch.own?.let { return it.name }
        // 2. The sender ID against the curated table.
        val normalized = normalizeSender(senderId)
        INSTITUTIONS
            .firstOrNull { inst -> inst.senderKeys.any { normalized.contains(it) } }
            ?.let { return it.name }
        matchAlias(normalized)?.let { return it.name }
        // 3. A bank mentioned outside any account context (a signature like
        //    "- Federal Bank") - weaker than the sender by design.
        bodyMatch.mentioned?.let { return it.name }
        // 4. Last resort: the normalized sender ID itself.
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
        val bodyMatch = matchBodyInstitution(body)
        bodyMatch.own?.let { return it.name }
        val normalized = normalizeSender(senderId)
        INSTITUTIONS
            .firstOrNull { inst -> inst.senderKeys.any { normalized.contains(it) } }
            ?.let { return it.name }
        matchAlias(normalized)?.let { return it.name }
        return bodyMatch.mentioned?.name
    }

    /**
     * Whether [name] resolves to a curated issuer that is a standalone CARD
     * product (a co-branded card like Scapia Federal). Only such issuers may
     * own an issuer-keyed, digit-less card account.
     */
    fun isCardProductIssuer(name: String?): Boolean {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        val inst = matchAlias(trimmed.uppercase()) ?: return false
        return inst.isIssuer && inst.isCardProduct
    }

    /**
     * Whether [name] resolves to a curated WALLET issuer (Pluxee, Paytm...).
     * Wallet products routinely send digit-less money SMS ("credited with
     * Rs.X towards Meal Wallet") - only such issuers may own an issuer-keyed,
     * digit-less wallet account, mirroring [isCardProductIssuer] for cards.
     */
    fun isWalletIssuer(name: String?): Boolean {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        val inst = matchAlias(trimmed.uppercase()) ?: return false
        return inst.isIssuer && inst.isWalletProduct
    }

    /**
     * Whether [name] resolves to a curated RETIREMENT scheme issuer (NPS
     * via either CRA). A tail-less retirement valuation may UPDATE the
     * scheme's sole existing account - never create one; the PRAN-tailed
     * shapes own the account's identity.
     */
    fun isRetirementIssuer(name: String?): Boolean {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        val inst = matchAlias(trimmed.uppercase()) ?: return false
        return inst.isIssuer && inst.isRetirementProduct
    }

    /**
     * Stable synthetic account key for an issuer-keyed account (a card or
     * wallet product whose SMS carries no digits): the canonical issuer name
     * with everything but letters and digits stripped, uppercased.
     * Deliberately non-numeric so it can never collide with a real last-4.
     */
    fun syntheticAccountKey(bankName: String): String = bankName.uppercase().filter { it.isLetterOrDigit() }

    /**
     * The single account-creation guardrail: whether [name] can plausibly
     * OWN an account or card - i.e. is a financial institution or wallet.
     *
     * Rationale: three real misattribution shapes all shared one root cause -
     * an `AccountEntity` was created from whatever name landed in
     * `bankName`, even when that name was a merchant ("at Paytm"), a payment
     * channel (CRED forwarding a card payment), or an ecommerce brand
     * (a Flipkart refund credited to a bank account). An account row must
     * only ever be created for a plausible issuer:
     *  - a curated institution whose kind is bank/wallet ([Institution.isIssuer]),
     *  - or an uncurated name that self-evidently names a bank ("...BANK..."),
     *  - or a name the [body] explicitly places in a card/account phrase
     *    ("linked to your <Name> Card", "<Name> A/c") - an unknown-but-real
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
     * The body's institution evidence, split by strength.
     *
     * [own] is the account's OWN institution: an alias in an ACCOUNT context
     * - "your Axis Bank credit card", "in HDFC Bank A/c xx8709", "Pluxee ...
     * wallet". Two refinements keep that honest:
     *  - an alias in a MERCHANT/VPA position ("at FLIPKART", "to VPA
     *    credcc@yesbank", "For IMPS -Federal bank-") is the counterparty and
     *    is discarded outright, whatever follows it - a UPI narration
     *    quoting "credit card bill" after "@yesbank" must not make Yes Bank
     *    the account's bank;
     *  - softer lead-ins ("via <bank>", "from <bank>", "to <bank>") discard
     *    the alias only when NO account context follows - "debited from
     *    HDFC Bank A/c xx8709" is the user's own account;
     *  - a context word that is itself part of ANOTHER institution's name
     *    does not count ("For SONYLIV ... Via: HDFC Bank" must not read
     *    "Bank" as Sony LIV's account context).
     *
     * [mentioned] is a self-evident bank name outside any account context
     * (a "- Federal Bank" signature): kept as a fallback BELOW the sender,
     * so a known sender naming another bank in passing keeps the account.
     */
    private class BodyInstitutions(
        val own: Institution?,
        val mentioned: Institution?,
    )

    private fun matchBodyInstitution(body: String): BodyInstitutions {
        if (body.isEmpty()) return NO_BODY_INSTITUTIONS
        val upper = body.uppercase()
        val allMatches =
            aliasIndex.flatMap { (regex, institution) ->
                regex.findAll(upper).map { Triple(it.range, institution, regex.pattern.contains("BANK")) }
            }
        var mentioned: Institution? = null
        for ((range, institution, isBankAlias) in allMatches) {
            val preceding = upper.substring(maxOf(0, range.first - NARRATION_LOOKBEHIND), range.first)
            if (HARD_NARRATION_REGEX.containsMatchIn(preceding)) continue
            if (hasOwnAccountContext(upper, range, institution, allMatches)) {
                return BodyInstitutions(own = institution, mentioned = mentioned)
            }
            if (SOFT_NARRATION_REGEX.containsMatchIn(preceding)) continue
            if (isBankAlias && mentioned == null) mentioned = institution
        }
        return BodyInstitutions(own = null, mentioned = mentioned)
    }

    /**
     * Whether an ACCOUNT-context word follows the alias match at [range] -
     * ignoring context words that sit inside a DIFFERENT institution's own
     * alias match (another bank's name is never this alias's account).
     */
    private fun hasOwnAccountContext(
        upper: String,
        range: IntRange,
        institution: Institution,
        allMatches: List<Triple<IntRange, Institution, Boolean>>,
    ): Boolean {
        val windowStart = range.last + 1
        val trailing = upper.substring(windowStart, minOf(upper.length, windowStart + 32))
        return ACCOUNT_CONTEXT_REGEX.findAll(trailing).any { context ->
            val absolute = (windowStart + context.range.first)..(windowStart + context.range.last)
            allMatches.none { (otherRange, other, _) ->
                other !== institution && absolute.first <= otherRange.last && absolute.last >= otherRange.first
            }
        }
    }

    private val NO_BODY_INSTITUTIONS = BodyInstitutions(own = null, mentioned = null)

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
     * Hard counterparty lead-ins directly before a body alias: remittance
     * rails ("For IMPS -Federal bank-", "NEFT-<bank>-"), a VPA handle
     * ("@yesbank"), or a merchant position ("at FLIPKART"). The alias is
     * the counterparty regardless of what follows. Anchored to the end of
     * the short preceding window; runs against uppercased text.
     */
    private val HARD_NARRATION_REGEX =
        Regex("(?:\\b(?:IMPS|NEFT|RTGS|ACH|ECS|NACH|UPI)\\b[\\s/:.-]{0,4}|\\bAT\\s{1,4}|@)$")

    /**
     * Soft lead-ins ("via <bank>", "from <bank>", "to <bank>") that yield
     * only when NO account context follows - "debited from HDFC Bank A/c
     * xx8709" is the user's own account, "via Federal Bank UPI" is a rail.
     */
    private val SOFT_NARRATION_REGEX =
        Regex("(?:\\bVIA\\b[\\s:]{0,4}|\\b(?:FROM|TO)\\s{1,4})$")

    /** Chars of context inspected for the narration regexes. */
    private const val NARRATION_LOOKBEHIND = 16

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
