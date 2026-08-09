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
 * The tables are pure pattern CONTENT - merchant keywords, courier names,
 * biller sender ids - community-editable JSON. The algorithms that consume
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

    /** Per-type reminder evidence rows for [ReminderTypeClassifier]. */
    val reminderEvidence: ReminderEvidenceTable by lazy {
        parseReminderEvidence(readResource("reminder_evidence.json"))
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
     * Parses the reminder evidence table. Rows are validated with the guard
     * library's load-time ReDoS rules ([GuardLibrary.validate]) - pattern
     * content in this table runs against every candidate reminder body, so
     * an unsafe or invalid row is skipped with a warning, never fatal.
     * `table_ref` / `sender_table_ref` values resolve against the assembled
     * [billers] regexes; unknown refs, unknown guard ids and rows with no
     * condition at all are skipped likewise.
     */
    internal fun parseReminderEvidence(json: String?): ReminderEvidenceTable =
        parseOrEmpty("reminder_evidence.json", json, ReminderEvidenceTable.EMPTY) { text ->
            val table = FORMAT.decodeFromString<ReminderEvidenceJson>(text)
            val types =
                table.types.associate { entry ->
                    entry.type to
                        TypeEvidence(
                            evidence = entry.evidence.mapNotNull { evidenceRow(entry.type, it) },
                            support = entry.support.mapNotNull { evidenceRow(entry.type, it) },
                        )
                }
            val fallback =
                table.fallbackPattern
                    ?.takeIf { validatedRegex("fallback", it) != null }
                    ?.let(::Regex) ?: NEVER_MATCH
            ReminderEvidenceTable(types, fallback)
        }

    private fun evidenceRow(
        type: String,
        row: EvidenceRowJson,
    ): EvidenceRow? {
        val bodyRegex =
            when {
                row.pattern != null -> validatedRegex(type, row.pattern) ?: return null
                row.tableRef != null -> tableRef(type, row.tableRef) ?: return null
                else -> null
            }
        val senderRegex =
            when {
                row.senderTableRef != null -> tableRef(type, row.senderTableRef) ?: return null
                else -> null
            }
        if (bodyRegex == null && senderRegex == null) {
            warn("reminder_evidence.json", "$type: row with no condition skipped")
            return null
        }
        val guard =
            row.notIfGuard?.let { name ->
                GuardId.entries.firstOrNull { it.id == name } ?: run {
                    warn("reminder_evidence.json", "$type: unknown guard '$name'; row skipped")
                    return null
                }
            }
        return EvidenceRow(
            bodyRegex = bodyRegex,
            senderRegex = senderRegex,
            score = row.score,
            notIfGuard = guard,
            onlyIfNoOtherEvidence = row.onlyIfNoOtherEvidence,
        )
    }

    /** Compiles [pattern] after the guard library's ReDoS validation. */
    private fun validatedRegex(
        context: String,
        pattern: String,
    ): Regex? {
        GuardLibrary.validate(pattern)?.let { reason ->
            warn("reminder_evidence.json", "$context: pattern rejected ($reason)")
            return null
        }
        return runCatching { Regex(pattern) }
            .onFailure { warn("reminder_evidence.json", "$context: pattern does not compile") }
            .getOrNull()
    }

    private fun tableRef(
        type: String,
        ref: String,
    ): Regex? =
        when (ref) {
            "insurer_names" -> billers.insurerNameRegex
            "bill_domains" -> billers.billDomainRegex
            "known_biller_senders" -> billers.knownBillerSenderRegex
            else -> {
                warn("reminder_evidence.json", "$type: unknown table ref '$ref'; row skipped")
                null
            }
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

    /** Matches nothing, ever - the safe value for an empty alternation. */
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

    @Serializable
    private data class ReminderEvidenceJson(
        val types: List<TypeEvidenceJson> = emptyList(),
        @SerialName("fallback_pattern") val fallbackPattern: String? = null,
    )

    @Serializable
    private data class TypeEvidenceJson(
        val type: String,
        val evidence: List<EvidenceRowJson> = emptyList(),
        val support: List<EvidenceRowJson> = emptyList(),
    )

    @Serializable
    private data class EvidenceRowJson(
        val pattern: String? = null,
        @SerialName("table_ref") val tableRef: String? = null,
        @SerialName("sender_table_ref") val senderTableRef: String? = null,
        val score: Int = 0,
        @SerialName("not_if_guard") val notIfGuard: String? = null,
        @SerialName("only_if_no_other_evidence") val onlyIfNoOtherEvidence: Boolean = false,
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

/**
 * Reminder-type evidence rows loaded from `tables/reminder_evidence.json`.
 * Pattern content and weights live in the data; the scoring algorithm -
 * threshold, tie-break order, bill-disqualifies-subscription - stays in
 * [ReminderTypeClassifier]. An empty table (malformed/missing asset) makes
 * every type score zero, so classification degrades to the fallback.
 */
class ReminderEvidenceTable(
    val types: Map<String, TypeEvidence>,
    /** Generic dated-instalment fallback used only when no type scores. */
    val fallback: Regex,
) {
    companion object {
        val EMPTY = ReminderEvidenceTable(emptyMap(), ParserTables.NEVER_MATCH)
    }
}

/** One type's rows: cumulative [evidence], then corroborating [support]. */
class TypeEvidence(
    val evidence: List<EvidenceRow>,
    val support: List<EvidenceRow>,
)

/**
 * One scored condition. Matches when every regex present matches its
 * target ([bodyRegex] against the body, [senderRegex] against the sender)
 * and [notIfGuard] - when set - does NOT match the body.
 */
class EvidenceRow(
    val bodyRegex: Regex?,
    val senderRegex: Regex?,
    val score: Int,
    val notIfGuard: GuardId?,
    /** Counts only when no earlier evidence row of the same type matched. */
    val onlyIfNoOtherEvidence: Boolean,
) {
    fun matches(
        sender: String,
        body: String,
    ): Boolean {
        if (notIfGuard != null && GuardLibrary.matches(notIfGuard, body)) return false
        if (bodyRegex != null && !bodyRegex.containsMatchIn(body)) return false
        if (senderRegex != null && !senderRegex.containsMatchIn(sender)) return false
        return true
    }
}
