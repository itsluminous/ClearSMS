package app.clearsms.domain.model

import java.time.LocalDate

/**
 * A rule extract resolved to a TYPED value.
 *
 * Rules declare WHICH capture is what kind of thing (an amount, a date, a
 * merchant name - see the `extract` / `extract_types` schema in
 * CONTRIBUTING.md); the parsing ALGORITHMS (the amount grammar, the
 * multi-format date normalisation, merchant normalisation) stay in Kotlin
 * and are applied ONCE, by the rule engine, when the extract is resolved.
 * Consumers read the typed [value][Amount.value] instead of re-parsing the
 * raw capture; [raw] is always the capture text exactly as matched, which
 * is what gets persisted and displayed.
 */
sealed interface ExtractedValue {
    /** The resolved capture text, exactly as it matched. */
    val raw: String

    /** A monetary amount (comma-grouped digits accepted). */
    data class Amount(
        override val raw: String,
        val value: Double,
    ) : ExtractedValue

    /** A calendar date in any of the supported SMS date formats. */
    data class Date(
        override val raw: String,
        val value: LocalDate,
    ) : ExtractedValue

    /**
     * A merchant / counterparty name. [normalized] is the cleaned human
     * descriptor, or null when the capture was pure reference noise and
     * must not surface as a title.
     */
    data class Merchant(
        override val raw: String,
        val normalized: String?,
    ) : ExtractedValue

    /** A transaction direction: `debit` or `credit`. */
    data class TxnType(
        override val raw: String,
        val value: TransactionType,
    ) : ExtractedValue

    /** Plain text; no parsing applies. */
    data class Text(
        override val raw: String,
    ) : ExtractedValue
}

/** Typed amount for [key], or null when absent or not amount-typed. */
fun Map<String, ExtractedValue>.amount(key: String): Double? = (this[key] as? ExtractedValue.Amount)?.value

/** Typed date for [key], or null when absent or not date-typed. */
fun Map<String, ExtractedValue>.date(key: String): LocalDate? = (this[key] as? ExtractedValue.Date)?.value

/** Normalized merchant for [key], or null when absent or rejected as noise. */
fun Map<String, ExtractedValue>.merchant(key: String): String? = (this[key] as? ExtractedValue.Merchant)?.normalized

/** Typed transaction direction for [key], or null when absent or unparseable. */
fun Map<String, ExtractedValue>.transactionType(key: String): TransactionType? = (this[key] as? ExtractedValue.TxnType)?.value
