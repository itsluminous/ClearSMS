package app.clearsms.domain.parser

import app.clearsms.domain.model.ParsedReminder
import app.clearsms.domain.model.ReminderType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Extracts bill / payment reminders (credit card bills, EMIs, insurance
 * premiums, subscription renewals, dated utility bills) from message bodies.
 *
 * Precision rules:
 * - A reminder MUST carry a due date, and the due-context keyword must be
 *   anchored to that date ("due on <date>", "pay by <date>", ...). A bare
 *   "due"/"bill"/"expir" somewhere in the body is not enough — that gate
 *   previously flooded Alerts with unrelated messages.
 * - Completed / settled events (payments received, refunds, reimbursement
 *   claims, debit/credit confirmations) are never reminders.
 * - The generic OTHER type additionally requires a recognized bill domain
 *   (electricity, water, broadband, ...); an untyped, undomained "bill"
 *   mention emits nothing.
 */
class ReminderParser {
    fun parse(
        sender: String,
        body: String,
    ): ParsedReminder? {
        // Completed/settled events are not actionable reminders.
        if (SETTLED_REGEX.containsMatchIn(body)) return null
        // The due keyword must be adjacent to a parseable date.
        val dueDate = findAnchoredDueDate(body) ?: return null
        val type = detectType(sender, body) ?: return null
        return ParsedReminder(
            type = type,
            dueDate = dueDate,
            totalDue =
                TOTAL_DUE_REGEX
                    .find(body)
                    ?.groupValues
                    ?.get(1)
                    ?.toAmount(),
            minDue =
                MIN_DUE_REGEX
                    .find(body)
                    ?.groupValues
                    ?.get(1)
                    ?.toAmount(),
            accountLast4 = ACCOUNT_REGEX.find(body)?.groupValues?.get(1),
            bankName = SenderNameResolver.bankNameFor(sender, body),
        )
    }

    private fun detectType(
        sender: String,
        body: String,
    ): ReminderType? =
        when {
            CREDIT_CARD_REGEX.containsMatchIn(body) -> ReminderType.CREDIT_CARD
            EMI_REGEX.containsMatchIn(body) -> ReminderType.EMI
            INSURANCE_REGEX.containsMatchIn(body) -> ReminderType.INSURANCE
            SUBSCRIPTION_REGEX.containsMatchIn(body) -> ReminderType.SUBSCRIPTION
            // Generic bills need a recognized domain; "bill" alone is too
            // weak — unless the SENDER is a recognized biller (utilities
            // often just say "your bill", e.g. broadband providers).
            BILL_DOMAIN_REGEX.containsMatchIn(body) -> ReminderType.OTHER
            BILL_WORD_REGEX.containsMatchIn(body) && KNOWN_BILLER_SENDER_REGEX.containsMatchIn(sender) -> ReminderType.OTHER
            else -> null
        }

    /** First due-date whose keyword is directly anchored to the date text. */
    private fun findAnchoredDueDate(body: String): LocalDate? {
        DUE_DATE_ANCHORS
            .firstNotNullOfOrNull { anchor ->
                anchor
                    .find(body)
                    ?.groupValues
                    ?.get(1)
                    ?.let(::parseDate)
            }?.let { return it }
        // Looser "Pay <...> by <date>" (e.g. "Pay Total Amount Due of Rs X by
        // 05-08-26", "Pay instantly by 05/08/2026"), accepted only when the
        // body actually talks about something being due.
        if (DUE_WORD_REGEX.containsMatchIn(body)) {
            PAY_BY_LOOSE_ANCHOR
                .find(body)
                ?.groupValues
                ?.get(1)
                ?.let(::parseDate)
                ?.let { return it }
        }
        return null
    }

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
        // ISO yyyy-MM-dd (rule extracts / round-tripped values).
        ISO_DATE_REGEX.find(text)?.let { match ->
            val (year, month, day) = match.destructured
            return buildDate(day.toInt(), month.toInt(), year.toInt())
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
        /**
         * Date fragment used to anchor due-context keywords: DD-MM-YY(YY),
         * DD/MM/YYYY, DD-MMM-YY(YY) or "5 Aug 2026".
         */
        const val DATE =
            "(?<!\\d)(?:\\d{1,2}[-/](?:\\d{1,2}|[A-Za-z]{3})[-/]\\d{2}(?:\\d{2})?|" +
                "\\d{1,2}[-\\s][A-Za-z]{3,9}[-\\s,]\\s?\\d{2}(?:\\d{2})?)(?!\\d)"

        /**
         * Due-context keywords anchored to a date. The keyword being merely
         * present somewhere in the body is NOT a signal — "due" and "expir"
         * match too much unrelated text.
         */
        val DUE_DATE_ANCHORS =
            listOf(
                "\\bdue\\s+(?:on|by)\\s*:?\\s*(?:date\\s+)?($DATE)",
                "\\bdue\\s+date\\s*(?:is|:)?\\s*($DATE)",
                "\\bdue\\s*:?\\s*($DATE)",
                "\\bpay(?:able)?\\s+(?:by|before|till|until|on\\s+or\\s+before)\\s*:?\\s*($DATE)",
                "\\b(?:to\\s+be\\s+)?paid\\s+by\\s+($DATE)",
                "\\blast\\s+date\\s+(?:for\\s+payment\\s+|of\\s+payment\\s+|to\\s+pay\\s+)?(?:is\\s+)?:?\\s*($DATE)",
                "\\brenew(?:al)?\\s+(?:on|by|before)\\s+($DATE)",
                "\\bexpir(?:es|ing|y)\\s*(?:on|date)?\\s*(?:is|:)?\\s*($DATE)",
                "\\bvalid\\s+(?:till|until|upto|up\\s+to)\\s+($DATE)",
                "\\bpayment\\s+due\\s+(?:on\\s+)?($DATE)",
            ).map { Regex("(?i)$it") }

        /**
         * "Pay <up to 100 chars> by <date>" — gated on the body mentioning
         * "due". Dots are allowed in the gap (amounts like Rs 1,234.56 sit
         * between "Pay" and "by" in real card statements).
         */
        val PAY_BY_LOOSE_ANCHOR = Regex("(?i)\\bpay\\b[^\\n]{0,100}?\\bby\\s+($DATE)")

        val DUE_WORD_REGEX = Regex("(?i)\\bdue\\b")

        /**
         * Completed / settled / reversed events. These describe money that
         * already moved (or is coming back), so they must never become
         * reminders — reimbursement claims and payment confirmations were
         * the bulk of the junk in the Alerts "Others" filter.
         */
        val SETTLED_REGEX =
            Regex(
                "(?i)payment\\s+(?:of\\s+\\S{0,20}\\s*)?(?:received|successful|processed)|" +
                    "\\breceived\\s+(?:your\\s+)?payment|" +
                    "successfully\\s+(?:paid|processed|credited|received)|" +
                    "\\bpaid\\s+successfully|" +
                    "thank\\s+you\\s+for\\s+(?:your\\s+)?(?:payment|paying)|" +
                    "has\\s+been\\s+(?:paid|received|credited|processed|settled|reimbursed|refunded)|" +
                    "\\breimburse(?:d|ment)\\b|\\brefund(?:ed)?\\b|\\bsettled\\b|" +
                    "\\bclaim\\s+(?:of|amount|no\\.?|number|id)\\b|" +
                    "\\bdebited\\b|\\bcredited\\b",
            )

        val CREDIT_CARD_REGEX = Regex("(?i)credit\\s*card|card\\s+(?:bill|statement|ending)")
        val EMI_REGEX = Regex("(?i)\\bEMI\\b|instal?lment")
        val INSURANCE_REGEX = Regex("(?i)insurance|premium|\\bpolicy\\b")
        val SUBSCRIPTION_REGEX = Regex("(?i)subscription|\\bplan\\b|renewal|\\brenew\\b|membership")

        /** Recognized bill domains required for the generic OTHER type. */
        val BILL_DOMAIN_REGEX =
            Regex(
                "(?i)electricity|\\bpower\\s+bill|water\\s+bill|\\bgas\\b|broadband|internet\\s+bill|" +
                    "landline|postpaid|\\bDTH\\b|\\bd2h\\b|\\brent\\b|property\\s+tax|\\btax\\b|" +
                    "maintenance\\s+(?:bill|fee|charge)|\\bfee\\b|\\bfees\\b|utility\\s+bill|municipal",
            )

        /** A literal bill mention — only meaningful from a known biller sender. */
        val BILL_WORD_REGEX = Regex("(?i)\\bbill\\b")

        /**
         * Utility / telecom / broadband biller sender ids whose "your bill"
         * messages are trusted even without a domain keyword in the body.
         */
        val KNOWN_BILLER_SENDER_REGEX =
            Regex(
                "(?i)ACTGRP|ACTFBN|ACTBBN|ACTCOR|AIRBIL|AIRTEL|JIOFBR|JIOBB|BSNL|VICARE|" +
                    "BSES|BESCOM|MSEDCL|TNEB|TSSPD|APSPDC|KSEB|PSPCL|UPPCL|WBSEDC|CESC|" +
                    "ADANI|TATAPW|TPDDL|TORRNT|IGL|MGL|MAHGAS|GAIL|HPCL|BPCL|IOCL",
            )

        /** DD-MM-YY or DD/MM/YYYY style dates. */
        val NUMERIC_DATE_REGEX = Regex("(?<!\\d)(\\d{1,2})[-/](\\d{1,2})[-/](\\d{2}(?:\\d{2})?)(?!\\d)")

        /** DD-MMM-YY / DD MMM YYYY style dates ("05-Aug-26", "5 Aug 2026"). */
        val MONTH_NAME_DATE_REGEX =
            Regex("(?i)(?<!\\d)(\\d{1,2})[-/\\s]([A-Za-z]{3})[-/\\s](\\d{2}(?:\\d{2})?)(?!\\d)")

        /** ISO yyyy-MM-dd, produced by rule extracts and LocalDate.toString(). */
        val ISO_DATE_REGEX = Regex("(?<!\\d)(\\d{4})-(\\d{2})-(\\d{2})(?!\\d)")

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
