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
 * 1. an institution named in the BODY (that is the account's bank even when
 *    the SMS arrives via an aggregator like a card-payment app),
 * 2. the sender ID matched against the institution table generated from
 *    `rules/brands/brands.json` (entries carrying an `is_issuer` field),
 * 3. the normalized sender ID itself — an account should never be nameless;
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
         * and telecoms (Airtel) are not — they appear in money messages as
         * merchants or conduits, never as the account's home.
         */
        val isIssuer: Boolean = true,
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
     * warning — resolution then falls back to normalized sender ids, never
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
