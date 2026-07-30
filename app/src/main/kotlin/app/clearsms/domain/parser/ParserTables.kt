package app.clearsms.domain.parser

import app.clearsms.domain.model.MerchantCategory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.logging.Logger

/**
 * Bundled lookup tables for the domain parsers, loaded once from classpath
 * resources compiled straight out of `rules/tables/` (see CONTRIBUTING.md).
 *
 * The tables are pure pattern CONTENT — merchant keywords, courier names,
 * biller sender ids — community-editable JSON. The algorithms that consume
 * them (scoring, arbitration, guardrails) stay in Kotlin.
 *
 * Loading is lazy and cheap (a few KB of JSON, parsed once per process) and
 * must never crash: a malformed or missing table degrades to an EMPTY table
 * with a logged warning, so a bad community edit can at worst lose a
 * convenience (a spend category, a courier name), never the app.
 *
 * Where a table feeds a regex, the regex is assembled HERE, in code, keeping
 * the ReDoS discipline: literal fragments are escaped, alternations stay
 * flat (no nested quantifiers), and case-insensitivity is applied as a
 * compile option. An empty table assembles to a never-matching regex.
 */
object ParserTables {
    /** Merchant-keyword patterns to spend category, tried in order. */
    val merchantCategories: List<Pair<Regex, MerchantCategory>> by lazy {
        parseMerchantCategories(readResource("merchant_categories.json"))
    }

    /** Courier/merchant name keys and brand registered domains. */
    val couriers: CourierTable by lazy {
        parseCouriers(readResource("couriers.json"))
    }

    /** Biller sender ids, insurer names and bill-domain patterns, as regexes. */
    val billers: BillerTable by lazy {
        parseBillers(readResource("billers.json"))
    }

    /** Bundled table text, or null (with a warning) when the resource is missing. */
    internal fun readResource(name: String): String? =
        try {
            ParserTables::class.java.classLoader
                ?.getResourceAsStream(name)
                ?.bufferedReader()
                ?.use { it.readText() }
                .also { if (it == null) warn(name, "resource not found") }
        } catch (e: Exception) {
            warn(name, e.toString())
            null
        }

    internal fun parseMerchantCategories(json: String?): List<Pair<Regex, MerchantCategory>> =
        parseOrEmpty("merchant_categories.json", json, emptyList()) { text ->
            FORMAT.decodeFromString<MerchantCategoryTable>(text).categories.mapNotNull { row ->
                val category = runCatching { MerchantCategory.valueOf(row.category) }.getOrNull()
                val regex = runCatching { Regex(row.pattern) }.getOrNull()
                if (category == null || regex == null) {
                    warn("merchant_categories.json", "skipping invalid row: ${row.category}")
                    null
                } else {
                    regex to category
                }
            }
        }

    internal fun parseCouriers(json: String?): CourierTable =
        parseOrEmpty("couriers.json", json, CourierTable.EMPTY) { text ->
            val table = FORMAT.decodeFromString<CourierTableJson>(text)
            CourierTable(
                merchants = table.merchants.map { it.match to it.name },
                brandDomains = table.brandDomains.map { it.domain to it.name },
            )
        }

    internal fun parseBillers(json: String?): BillerTable =
        parseOrEmpty("billers.json", json, BillerTable.EMPTY) { text ->
            val table = FORMAT.decodeFromString<BillerTableJson>(text)
            BillerTable(
                // Sender ids are literals: escaped, then OR-ed flat.
                knownBillerSenderRegex = assembleAlternation(table.knownBillerSenderIds.map { Regex.escape(it) }),
                insurerNameRegex = assembleAlternation(table.insurerNamePatterns),
                billDomainRegex = assembleAlternation(table.billDomainPatterns),
            )
        }

    /**
     * Case-insensitive flat alternation of [fragments]. Fragments that do not
     * compile on their own are dropped with a warning (never crash on a bad
     * community edit); an empty list yields a regex that matches nothing.
     */
    internal fun assembleAlternation(fragments: List<String>): Regex {
        val valid =
            fragments.filter { fragment ->
                runCatching { Regex(fragment) }.isSuccess.also { ok ->
                    if (!ok) warn("billers.json", "skipping invalid pattern: $fragment")
                }
            }
        if (valid.isEmpty()) return NEVER_MATCH
        return Regex(valid.joinToString("|"), RegexOption.IGNORE_CASE)
    }

    private fun <T> parseOrEmpty(
        name: String,
        json: String?,
        empty: T,
        parse: (String) -> T,
    ): T {
        if (json == null) return empty
        return try {
            parse(json)
        } catch (e: Exception) {
            warn(name, e.toString())
            empty
        }
    }

    private fun warn(
        name: String,
        reason: String,
    ) {
        Logger.getLogger("ClearSMS").warning("Bundled table $name unusable ($reason); falling back to an empty table")
    }

    /** Matches nothing, ever — the safe value for an empty alternation. */
    internal val NEVER_MATCH = Regex("(?!)")

    private val FORMAT = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class MerchantCategoryTable(
        val categories: List<MerchantCategoryRow> = emptyList(),
    )

    @Serializable
    private data class MerchantCategoryRow(
        val pattern: String,
        val category: String,
    )

    @Serializable
    private data class CourierTableJson(
        val merchants: List<CourierMerchantRow> = emptyList(),
        @SerialName("brand_domains") val brandDomains: List<BrandDomainRow> = emptyList(),
    )

    @Serializable
    private data class CourierMerchantRow(
        val match: String,
        val name: String,
    )

    @Serializable
    private data class BrandDomainRow(
        val domain: String,
        val name: String,
    )

    @Serializable
    private data class BillerTableJson(
        @SerialName("known_biller_sender_ids") val knownBillerSenderIds: List<String> = emptyList(),
        @SerialName("insurer_name_patterns") val insurerNamePatterns: List<String> = emptyList(),
        @SerialName("bill_domain_patterns") val billDomainPatterns: List<String> = emptyList(),
    )
}

/**
 * Courier / merchant lookup keys ([merchants], matched uppercased as
 * substrings of sender ids and bodies, in table order) and brand registered
 * domains ([brandDomains], matched against URL hostnames only).
 */
class CourierTable(
    val merchants: List<Pair<String, String>>,
    val brandDomains: List<Pair<String, String>>,
) {
    companion object {
        val EMPTY = CourierTable(emptyList(), emptyList())
    }
}

/**
 * Assembled biller-side regexes for [ReminderTypeClassifier]: which sender
 * ids are trusted billers, which brand names are insurers, and which domain
 * words mark a bill. The evidence WEIGHTS and arbitration stay in code.
 */
class BillerTable(
    val knownBillerSenderRegex: Regex,
    val insurerNameRegex: Regex,
    val billDomainRegex: Regex,
) {
    companion object {
        val EMPTY = BillerTable(ParserTables.NEVER_MATCH, ParserTables.NEVER_MATCH, ParserTables.NEVER_MATCH)
    }
}
