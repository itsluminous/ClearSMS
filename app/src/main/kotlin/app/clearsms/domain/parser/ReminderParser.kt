package app.clearsms.domain.parser

import app.clearsms.domain.model.ParsedReminder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Extracts bill / payment reminders (credit card bills, EMIs, deposit
 * installments, insurance premiums, subscription renewals, dated utility
 * bills) from message bodies.
 *
 * Precision rules:
 * - A reminder MUST carry a due date, and the due-context keyword must be
 *   anchored to that date ("due on <date>", "pay by <date>", ...). A bare
 *   "due"/"bill"/"expir" somewhere in the body is not enough - that gate
 *   previously flooded Alerts with unrelated messages.
 * - Completed / settled events (payments received, thank-you-for-payment
 *   confirmations, refunds, reimbursement claims, debit/credit
 *   confirmations) are never reminders, even when the body also mentions a
 *   premium or a due date.
 * - A reminder needs a payment OBLIGATION: marketing/investment pitches and
 *   voucher/coupon expiries are rejected outright (the `marketing_pitch`
 *   and `voucher` guards) - a date plus a financial-sounding word obligates
 *   nothing.
 * - The TYPE is decided by [ReminderTypeClassifier], which scores anchored
 *   evidence per type (see its KDoc table) instead of first-keyword-wins -
 *   so identical bills from one biller always land in one bucket.
 *
 * Beyond the due date every reminder tries to carry the amount due (total
 * plus minimum where present - see [TOTAL_DUE_PATTERNS]) and a short human
 * [ParsedReminder.label] describing what the bill is for, falling back to a
 * digit-masked excerpt of the message when nothing structured is found.
 */
class ReminderParser {
    private val typeClassifier = ReminderTypeClassifier()

    fun parse(
        sender: String,
        body: String,
    ): ParsedReminder? {
        // Completed/settled events are not actionable reminders.
        if (GuardLibrary.matches(GuardId.SETTLED_PAYMENT, body)) return null
        // A reminder needs a payment OBLIGATION on the user's own product.
        // Marketing pitches (investment upsells) and voucher/coupon expiries
        // carry dates and financial words but obligate nothing - they must
        // never surface in Alerts.
        if (GuardLibrary.matches(GuardId.MARKETING_PITCH, body)) return null
        if (GuardLibrary.matches(GuardId.VOUCHER, body)) return null
        // The due keyword must be adjacent to a parseable date.
        val dueDate = findAnchoredDueDate(body) ?: return null
        val type = typeClassifier.classify(sender, body) ?: return null
        val minDue = firstAmount(body, MIN_DUE_PATTERNS)
        return ParsedReminder(
            type = type,
            dueDate = dueDate,
            totalDue = resolveTotalAgainstMin(body, firstAmount(body, TOTAL_DUE_PATTERNS), minDue),
            minDue = minDue,
            accountLast4 = ACCOUNT_REGEX.find(body)?.groupValues?.get(1),
            bankName = SenderNameResolver.bankNameFor(sender, body),
            label = extractLabel(body),
        )
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
        // Month-first "August 08, 2026" / "Aug 8 2026" (CRED bill statements).
        // The first three letters of every English month ARE its standard
        // abbreviation, so a full name parses through the same MMM formatter;
        // a non-month word simply fails the parse and yields null.
        MONTH_FIRST_DATE_REGEX.find(text)?.let { match ->
            val monthName =
                match.groupValues[1]
                    .take(3)
                    .lowercase()
                    .replaceFirstChar { it.uppercase() }
            val day = match.groupValues[2].toInt()
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

    // region label

    /**
     * Short human description of what the reminder is for: a structured
     * extract (deposit reference, biller product, policy plan, card product,
     * subscription plan) when one is recognizable, else a digit-masked
     * excerpt of the message - an excerpt is far better than a bare date.
     */
    private fun extractLabel(body: String): String? {
        structuredLabel(body)?.let { return clip(it) }
        return clip(excerpt(body))
    }

    private fun structuredLabel(body: String): String? {
        // "HDFC Bank RD 12345" -> "RD xx2345" (never a full reference).
        DEPOSIT_REF_REGEX.find(body)?.let { match ->
            val kind = match.groupValues[1].uppercase()
            val ref = match.groupValues[2]
            return "$kind xx${ref.takeLast(4)}"
        }
        // "Bill for your Airtel Mobile 98xxxxxx10 ..." -> "Airtel Mobile bill".
        BILL_FOR_REGEX.find(body)?.let { match ->
            return "${match.groupValues[1].trim()} bill"
        }
        // "your ACT Fibernet Broadband bill of Rs.1178.82" -> the biller
        // product ("ACT Fibernet Broadband bill").
        BILLER_BILL_OF_REGEX.find(body)?.let { match ->
            return "${match.groupValues[1].trim()} bill"
        }
        // "... your ICICIPru policy ICICI Pru iProtect Smart policy no H123" ->
        // "ICICI Pru iProtect Smart".
        POLICY_PLAN_REGEX.find(body)?.let { return it.groupValues[1].trim() }
        POLICY_FOR_REGEX.find(body)?.let { return "${it.groupValues[1].trim()} policy" }
        // "towards Autopay for YouTube, UPI Mandate, ..." -> "YouTube autopay".
        AUTOPAY_PAYEE_REGEX.find(body)?.let { return "${it.groupValues[1].trim()} autopay" }
        // "Tata Neu Infinity HDFC Bank Credit Card" -> the card product.
        CARD_PRODUCT_REGEX.find(body)?.let { return "${it.groupValues[1].trim()} Credit Card" }
        // "your Netflix plan / postpaid connection" -> "<name> plan".
        PLAN_REGEX.find(body)?.let { match ->
            return "${match.groupValues[1].trim()} ${match.groupValues[2].lowercase()}"
        }
        return null
    }

    /**
     * Single-line excerpt of the message with long digit runs masked down to
     * their last 4 digits, so a fallback label never leaks a full account,
     * policy or phone number.
     */
    private fun excerpt(body: String): String =
        body
            .replace(WHITESPACE_REGEX, " ")
            .trim()
            .replace(LONG_DIGIT_RUN_REGEX) { "xx" + it.value.takeLast(4) }

    /** Clips to [MAX_LABEL_LENGTH] chars at a word boundary. */
    private fun clip(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.length <= MAX_LABEL_LENGTH) return trimmed
        val cut = trimmed.take(MAX_LABEL_LENGTH)
        val atWord = cut.substringBeforeLast(' ', cut)
        return "${atWord.trimEnd()}\u2026"
    }

    // endregion

    private fun firstAmount(
        body: String,
        patterns: List<Regex>,
    ): Double? =
        patterns.firstNotNullOfOrNull { pattern ->
            pattern
                .find(body)
                ?.groupValues
                ?.get(1)
                ?.toAmount()
        }

    /**
     * Enforces the invariant totalDue >= minDue. A parse where the "total"
     * comes out smaller than the minimum is a mis-parse (a minimum-due phrase
     * captured by a total pattern), so instead of storing it the total is
     * re-resolved: every total-pattern match in the body is scanned for the
     * first amount that satisfies the invariant, and when none does the
     * total is dropped rather than stored wrong.
     */
    private fun resolveTotalAgainstMin(
        body: String,
        total: Double?,
        min: Double?,
    ): Double? {
        if (total == null || min == null || total >= min) return total
        return TOTAL_DUE_PATTERNS
            .asSequence()
            .flatMap { it.findAll(body) }
            .mapNotNull { it.groupValues[1].toAmount() }
            .firstOrNull { it >= min }
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
        const val MAX_LABEL_LENGTH = 40

        /**
         * Date fragment used to anchor due-context keywords: DD-MM-YY(YY),
         * DD/MM/YYYY, DD-MMM-YY(YY), "5 Aug 2026" or the month-first
         * "August 08, 2026" (CRED bill statements).
         */
        const val DATE =
            "(?<!\\d)(?:\\d{1,2}[-/](?:\\d{1,2}|[A-Za-z]{3})[-/]\\d{2}(?:\\d{2})?|" +
                "\\d{1,2}[-\\s][A-Za-z]{3,9}[-\\s,]\\s?\\d{2}(?:\\d{2})?|" +
                "[A-Za-z]{3,9}\\s\\d{1,2},?\\s\\d{2}(?:\\d{2})?)(?!\\d)"

        /**
         * Currency amount with capture group: `Rs. 1,234.56`, `INR 500`,
         * `₹99`, the statement style `INR  Dr. 4,255.00`, and the HDFC
         * autopay style `INR.649.00` (dot directly after INR).
         */
        const val AMOUNT = "(?:INR\\.?|Rs\\.?|\\u20b9)\\s*(?:Dr\\.?\\s*)?([\\d,]+(?:\\.\\d{1,2})?)"

        /**
         * Amount with the currency symbol OPTIONAL - for phrasings where banks
         * omit it after a very strong money anchor ("EMI DUE : 4131",
         * "Due: 1162.3"). The trailing `(?![-/])` stops a date's day being read
         * as the amount ("Due: 15-AUG-23" -> 15 is rejected), so this must only
         * be used with an explicit money-context anchor, never bare.
         */
        const val AMOUNT_LOOSE =
            "(?:INR|Rs\\.?|\\u20b9)?\\s*([\\d,]+(?:\\.\\d{1,2})?)(?![-/])"

        /**
         * Due-context keywords anchored to a date. The keyword being merely
         * present somewhere in the body is NOT a signal - "due" and "expir"
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
                // Upcoming autopay/mandate/standing-instruction debit: "will
                // be debited for Rs 59.00 on 03-Jul-26", "will be debited on
                // 12/08/2026 from HDFC Bank Card", "will be debited from
                // your bank a/c on 15-08-2026". Future tense IS the due
                // signal - the debit has not happened yet, and the date is
                // when it will. Bounded gap; a Tata Neu/CRED collect request
                // ("will be debited from your account. To authorise, click
                // ...") carries no date and never anchors.
                "\\bwill\\s+be\\s+debited\\b[^\\n]{0,40}?\\bon\\s+($DATE)",
            ).map { Regex("(?i)$it") }

        /**
         * "Pay <up to 100 chars> by <date>" - gated on the body mentioning
         * "due". Dots are allowed in the gap (amounts like Rs 1,234.56 sit
         * between "Pay" and "by" in real card statements).
         */
        val PAY_BY_LOOSE_ANCHOR = Regex("(?i)\\bpay\\b[^\\n]{0,100}?\\bby\\s+($DATE)")

        val DUE_WORD_REGEX = Regex("(?i)\\bdue\\b")

        /** DD-MM-YY or DD/MM/YYYY style dates. */
        val NUMERIC_DATE_REGEX = Regex("(?<!\\d)(\\d{1,2})[-/](\\d{1,2})[-/](\\d{2}(?:\\d{2})?)(?!\\d)")

        /** DD-MMM-YY / DD MMM YYYY style dates ("05-Aug-26", "5 Aug 2026"). */
        val MONTH_NAME_DATE_REGEX =
            Regex("(?i)(?<!\\d)(\\d{1,2})[-/\\s]([A-Za-z]{3})[-/\\s](\\d{2}(?:\\d{2})?)(?!\\d)")

        /** Month-first "August 08, 2026" / "Aug 8 2026" style dates. */
        val MONTH_FIRST_DATE_REGEX =
            Regex("(?i)\\b([A-Za-z]{3,9})\\s+(\\d{1,2}),?\\s+(\\d{2}(?:\\d{2})?)(?!\\d)")

        /** ISO yyyy-MM-dd, produced by rule extracts and LocalDate.toString(). */
        val ISO_DATE_REGEX = Regex("(?<!\\d)(\\d{4})-(\\d{2})-(\\d{2})(?!\\d)")

        /**
         * Amount-due phrasings seen in real bank/biller SMS, most explicit
         * first. Every pattern is anchored to a due / bill / statement /
         * premium / EMI / installment context so an unrelated amount in the
         * body is never picked up.
         */
        val TOTAL_DUE_PATTERNS =
            listOf(
                // "Total due Rs.15,240", "Total amount due: INR Dr. 4,255.00",
                // "pay total due of Rs 4444.55" - and the statement style
                // where "due" only follows the MINIMUM line: "Total amt:
                // INR  Dr. 12374.57". "due" is required after a bare "total"
                // but optional once "amt"/"amount" anchors the phrase, so
                // "Total amt:", "Total amount:", "Total amt due" and
                // "Total Due" all map here.
                "total\\s+(?:(?:amt|amount)\\s*(?:due)?|due)(?:\\s+is)?\\s*(?:of\\s+)?[:\\s]*$AMOUNT",
                // "Total of Rs 5,432.10 or minimum of Rs 270 is due by".
                "total\\s+of\\s+$AMOUNT",
                // "Payment of INR 12345 for Axis Bank Credit Card ... is due on".
                "payment\\s+of\\s+$AMOUNT[^\\n]{0,80}?\\bis\\s+due",
                // "statement of INR 15240.00 with due date".
                "statement\\s+of\\s+$AMOUNT",
                // "Amount to be paid: Rs 649.00", "Amount payable Rs 649".
                "amount\\s+(?:to\\s+be\\s+paid|payable)\\s*:?\\s*$AMOUNT",
                // "Amount INR 12,345.00 Due on 05-AUG-26" (deposit installments).
                "\\bamount\\s*:?\\s*$AMOUNT\\s+due\\b",
                // "EMI of Rs.12,500 ... is due".
                "\\bEMI\\s+of\\s+$AMOUNT",
                // "installment of Rs 2,000 is due".
                "instal?lment\\s+of\\s+$AMOUNT",
                // "premium of Rs.24,000 is due".
                "premium\\s+of\\s+$AMOUNT",
                // "Premium due on 05-May-2026 for your ... policy no H123 for Rs. 5000"
                // and the variant with the amount AFTER the policy number,
                // introduced by "of": "... policy no. H4847657 of Rs. 1250".
                "(?:premium|policy)[^\\n]{0,120}?\\b(?:for|of)\\s+$AMOUNT",
                // Upcoming autopay/standing-instruction debits - future tense,
                // so this is an obligation, not a movement (the transaction
                // parser rejects the same phrasing): "will be debited for
                // Rs 59.00 on 03-Jul-26" and "INR 649.00 will be debited on
                // 12/08/2026 from HDFC Bank Card".
                "will\\s+be\\s+debited\\s+for\\s+$AMOUNT",
                "$AMOUNT\\s+will\\s+be\\s+debited\\b",
                // "Bill amount Rs 890", "bill of Rs.2,340".
                "bill\\s+(?:amount|of)\\s*:?\\s*$AMOUNT",
                // "Your bill for JUL-26 on A/C xx1550 is INR 1178.82" - the
                // amount stated with "is" after a bill/statement phrase. The
                // gap is bounded and the currency must follow "is" directly,
                // so an unrelated amount elsewhere in the body never binds.
                "\\b(?:bill|statement)\\s+for\\s+[^\\n]{0,60}?\\bis\\s+$AMOUNT",
                // "Amount Due" then the value (often on the next line):
                // "Amount Due\nRs.4961 on HDFC Bank Credit Card 2863".
                "\\bamount\\s+due\\s*:?\\s*$AMOUNT_LOOSE",
                // "EMI DUE : 4131" - currency frequently omitted. Won't match
                // "EMI Due date:" (no number follows the colon there).
                "\\bEMI\\s+due\\s*:?\\s*$AMOUNT_LOOSE",
                // Generic "Due: 1162.3" fallback. Colon REQUIRED (never matches
                // "due on <date>"/"due by <date>"), the AMOUNT_LOOSE date-guard
                // rejects "Due date: 15-AUG-23", and the lookbehinds stop it
                // firing inside "amt due:" / "amount due:" / "min due:" /
                // "total due:" (those are the specific total/min patterns above,
                // so the minimum is never mis-read as the total). Last, so every
                // more specific phrasing wins first.
                "(?<!amt )(?<!min )(?<!amount )(?<!total )\\bdue\\s*:\\s*$AMOUNT_LOOSE",
            ).map { Regex("(?i)$it") }

        /** Minimum-due phrasings; kept alongside the total when both exist. */
        val MIN_DUE_PATTERNS =
            listOf(
                // "Min due Rs.762", "minimum amount due of INR 1044",
                // "Minimum amt due: INR Dr. 212.75".
                "min(?:imum)?\\s+(?:amt|amount)?\\s*due(?:\\s+is)?\\s*(?:of\\s+)?[:\\s]*$AMOUNT",
                // "or minimum of Rs 270.55 is due by".
                "minimum\\s+of\\s+$AMOUNT",
            ).map { Regex("(?i)$it") }

        /**
         * Account/card tail. `\d*?` lets a LONG account number ("A/C
         * 102017641550") yield its last 4 digits as the stored masked tail -
         * the same shape other billers produce - without changing how short
         * masked tails ("XX0266") are captured.
         */
        val ACCOUNT_REGEX =
            Regex(
                "(?i)(?:a/c|acct|account|card)\\s*(?:no\\.?|number)?\\s*(?:ending\\s*)?(?:in\\s+|with\\s+)?[Xx*]*\\d*?(\\d{3,4})(?!\\d)",
            )

        // region label patterns

        /** "RD 12345", "FD no 987654" - deposit reference. */
        val DEPOSIT_REF_REGEX = Regex("(?i)\\b(RD|FD)\\s*(?:no\\.?\\s*)?(\\d{3,})")

        /** "Bill for your Airtel Mobile 98xxx" - the biller product. */
        val BILL_FOR_REGEX =
            Regex("(?i)bill\\s+for\\s+your\\s+([A-Za-z][A-Za-z ]{2,30}?)(?=\\s*(?:\\d|x{2,}|no\\b|number|is\\b|:|,|\\.))")

        /** "your ACT Fibernet Broadband bill of Rs.X" - the biller product. */
        val BILLER_BILL_OF_REGEX = Regex("(?i)your\\s+([A-Za-z][A-Za-z0-9 ]{2,34}?)\\s+bill\\s+of\\b")

        /** "your <brand> policy <PLAN NAME> policy no H123" - the plan name. */
        val POLICY_PLAN_REGEX = Regex("(?i)policy\\s+([A-Za-z][A-Za-z0-9 .&'-]{3,38}?)\\s+policy\\s+no\\b")

        /** "for your <NAME> policy" - the policy descriptor. */
        val POLICY_FOR_REGEX = Regex("(?i)for\\s+your\\s+([A-Za-z][A-Za-z0-9 .&'-]{2,38}?)\\s+policy\\b")

        /** "<Product> Credit Card" - the card product incl. bank. */
        val CARD_PRODUCT_REGEX =
            Regex("(?i)(?:\\b(?:your|for|the|on)\\s+)?\\b([A-Z][A-Za-z ]{2,33}?)\\s+credit\\s+card\\b")

        /** "your <name> plan/subscription/membership/postpaid". */
        val PLAN_REGEX =
            Regex("(?i)your\\s+([A-Za-z][A-Za-z0-9 ]{2,30}?)\\s+(plan|subscription|membership|pack|postpaid)\\b")

        /** "towards Autopay for YouTube," - the autopay payee. */
        val AUTOPAY_PAYEE_REGEX =
            Regex("(?i)\\btowards\\s+Autopay\\s+for\\s+([A-Za-z][A-Za-z0-9 &.'-]{1,29}?)(?=[,.\\n]|$)")

        val WHITESPACE_REGEX = Regex("\\s+")

        /** Digit runs long enough to be an account / phone / policy number. */
        val LONG_DIGIT_RUN_REGEX = Regex("\\d{5,}")

        // endregion
    }
}
