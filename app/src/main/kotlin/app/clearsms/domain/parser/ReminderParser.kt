package app.clearsms.domain.parser

import app.clearsms.domain.model.ParsedReminder
import app.clearsms.domain.model.ReminderType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Extracts bill / payment reminders (credit card bills, EMIs, insurance
 * premiums, subscription renewals) from message bodies.
 */
class ReminderParser {
    fun parse(
        sender: String,
        body: String,
    ): ParsedReminder? {
        if (!DUE_CONTEXT.containsMatchIn(body)) return null
        val type = detectType(body) ?: return null
        val dueDate = extractDueDate(body)
        val totalDue =
            TOTAL_DUE_REGEX
                .find(body)
                ?.groupValues
                ?.get(1)
                ?.toAmount()
        val minDue =
            MIN_DUE_REGEX
                .find(body)
                ?.groupValues
                ?.get(1)
                ?.toAmount()
        // A reminder needs at least a date or an amount to be actionable.
        if (dueDate == null && totalDue == null && minDue == null) return null
        return ParsedReminder(
            type = type,
            dueDate = dueDate,
            totalDue = totalDue,
            minDue = minDue,
            accountLast4 = ACCOUNT_REGEX.find(body)?.groupValues?.get(1),
            bankName = SenderNameResolver.bankNameFor(sender, body),
        )
    }

    private fun detectType(body: String): ReminderType? =
        when {
            CREDIT_CARD_REGEX.containsMatchIn(body) -> ReminderType.CREDIT_CARD
            EMI_REGEX.containsMatchIn(body) -> ReminderType.EMI
            INSURANCE_REGEX.containsMatchIn(body) -> ReminderType.INSURANCE
            SUBSCRIPTION_REGEX.containsMatchIn(body) -> ReminderType.SUBSCRIPTION
            BILL_REGEX.containsMatchIn(body) -> ReminderType.OTHER
            else -> null
        }

    private fun extractDueDate(body: String): LocalDate? = parseDate(body)

    /** Parses the first recognizable DD-MM-YY(YY) or DD-MMM-YY(YY) date in [text]. */
    fun parseDate(text: String): LocalDate? {
        NUMERIC_DATE_REGEX.find(text)?.let { match ->
            val (day, month, year) = match.destructured
            return buildDate(day.toInt(), month.toInt(), year.toInt())
        }
        MONTH_NAME_DATE_REGEX.find(text)?.let { match ->
            val day = match.groupValues[1].toInt()
            val monthName = match.groupValues[2].lowercase().replaceFirstChar { it.uppercase() }
            val year = match.groupValues[3].toInt()
            return try {
                LocalDate.parse(
                    "$day-$monthName-${normalizeYear(year)}",
                    DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH),
                )
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    private fun buildDate(
        day: Int,
        month: Int,
        year: Int,
    ): LocalDate? =
        try {
            LocalDate.of(normalizeYear(year), month, day)
        } catch (_: Exception) {
            null
        }

    private fun normalizeYear(year: Int): Int = if (year < 100) 2000 + year else year

    private fun String.toAmount(): Double? = replace(",", "").toDoubleOrNull()

    private companion object {
        val DUE_CONTEXT = Regex("(?i)\\bdue\\b|\\bpay\\s+by\\b|\\brenew\\b|expir")

        val CREDIT_CARD_REGEX = Regex("(?i)credit\\s*card|card\\s+(?:bill|statement|ending)")
        val EMI_REGEX = Regex("(?i)\\bEMI\\b|instal?lment")
        val INSURANCE_REGEX = Regex("(?i)insurance|premium|\\bpolicy\\b")
        val SUBSCRIPTION_REGEX = Regex("(?i)subscription|\\bplan\\b|renewal|\\brenew\\b|membership")
        val BILL_REGEX = Regex("(?i)\\bbill\\b|payment\\s+(?:due|reminder)")

        /** DD-MM-YY or DD/MM/YYYY style dates. */
        val NUMERIC_DATE_REGEX = Regex("(?<!\\d)(\\d{1,2})[-/](\\d{1,2})[-/](\\d{2}(?:\\d{2})?)(?!\\d)")

        /** DD-MMM-YY / DD MMM YYYY style dates ("05-Aug-26", "5 Aug 2026"). */
        val MONTH_NAME_DATE_REGEX =
            Regex("(?i)(?<!\\d)(\\d{1,2})[-/\\s]([A-Za-z]{3})[-/\\s](\\d{2}(?:\\d{2})?)(?!\\d)")

        val TOTAL_DUE_REGEX =
            Regex("(?i)total\\s+(?:amt|amount)?\\s*due(?:\\s+is)?\\s*[:\\s]*(?:INR|Rs\\.?|\\u20b9)\\s*([\\d,]+(?:\\.\\d{1,2})?)")

        val MIN_DUE_REGEX =
            Regex(
                "(?i)min(?:imum)?\\s+(?:amt|amount)?\\s*due(?:\\s+is)?\\s*[:\\s]*(?:INR|Rs\\.?|\\u20b9)\\s*([\\d,]+(?:\\.\\d{1,2})?)",
            )

        val ACCOUNT_REGEX =
            Regex("(?i)(?:a/c|acct|account|card)\\s*(?:no\\.?|number)?\\s*(?:ending\\s*)?(?:in\\s+|with\\s+)?[Xx*]*(\\d{3,4})(?!\\d)")
    }
}
