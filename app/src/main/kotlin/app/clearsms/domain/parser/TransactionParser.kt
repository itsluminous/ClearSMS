package app.clearsms.domain.parser

import app.clearsms.domain.model.AccountType
import app.clearsms.domain.model.MerchantCategory
import app.clearsms.domain.model.ParsedTransaction
import app.clearsms.domain.model.TransactionType

/**
 * Extracts debit/credit transactions from bank SMS bodies.
 *
 * A message is treated as a transaction only when it contains BOTH a currency
 * amount (₹ / Rs / INR) and a debit or credit keyword; this keeps OTPs and
 * promotional messages ("50% off up to Rs.100") from producing transactions.
 */
class TransactionParser {
    fun parse(
        sender: String,
        body: String,
    ): ParsedTransaction? {
        // A statement / bill notice ("Statement is sent...", "E-statement of
        // ... has been mailed") reports money OWED, not money moved. Its
        // verbs ("sent", "generated") and its "Total of Rs X ... is due"
        // amounts satisfy the transaction heuristics, so the notice phrases
        // are scrubbed BEFORE parsing: what remains carries no completed
        // debit/credit verb and the message stays a reminder only.
        val effectiveBody = STATEMENT_NOTICE_REGEX.replace(body, " ")
        val type = detectType(effectiveBody) ?: return null

        val balanceMatch = BALANCE_REGEX.find(effectiveBody)
        val balance = balanceMatch?.groupValues?.get(1)?.toAmount()

        // Amounts inside the balance or an "Avl Limit/Lmt" phrase are state,
        // not the transaction; excluding them keeps "Avl Limit: INR 286368.5"
        // from becoming the amount of a foreign-currency card spend.
        val excluded =
            listOfNotNull(balanceMatch?.range) + AVAILABLE_LIMIT_REGEX.findAll(effectiveBody).map { it.range }
        val domesticAmount =
            AMOUNT_REGEX
                .findAll(effectiveBody)
                .firstOrNull { match -> excluded.none { match.range.first in it } }
                ?.groupValues
                ?.get(1)
                ?.toAmount()
        val foreign = if (domesticAmount == null) FOREIGN_AMOUNT_REGEX.find(effectiveBody) else null
        val amount = domesticAmount ?: foreign?.groupValues?.get(2)?.toAmount() ?: return null

        val merchant = extractMerchant(effectiveBody)
        val resolvedBank = SenderNameResolver.bankNameFor(sender, body)
        // The issuer comes from the sender / card-account phrase, NEVER from
        // the "at <merchant>" clause; a resolved name that is not a plausible
        // issuer (CRED, Flipkart, ...) is demoted to the merchant slot so it
        // can never spawn an account (see SenderNameResolver.isPlausibleIssuer).
        val bankIsIssuer = SenderNameResolver.isPlausibleIssuer(resolvedBank, body)
        val title = merchant ?: resolvedBank?.takeIf { !bankIsIssuer }
        return ParsedTransaction(
            amount = amount,
            type = type,
            merchantName = title,
            accountLast4 = extractAccountLast4(effectiveBody),
            bankName = resolvedBank?.takeIf { bankIsIssuer },
            balance = balance,
            referenceNumber =
                REFERENCE_REGEX.find(effectiveBody)?.let { match ->
                    match.groupValues[1].ifEmpty { match.groupValues[2] }.ifEmpty { null }
                },
            merchantCategory = categorize(title, effectiveBody),
            accountType = detectAccountType(effectiveBody),
        )
    }

    /**
     * Parses a standalone balance statement ("Available Bal in HDFC Bank
     * A/c XX8709 as on yesterday:27-JUL-26 is INR 40,194.56") — state
     * reported, no money moved. Returns null whenever the body carries a
     * real transaction: there the balance is a secondary field of the
     * transaction, never a balance-only update. The bank survives only when
     * it is a plausible issuer (the merchants-never-become-accounts
     * guardrail), so a balance mention can never spawn a merchant account.
     */
    fun parseBalanceStatement(
        sender: String,
        body: String,
    ): BalanceStatement? {
        if (parse(sender, body) != null) return null
        val balance =
            STATEMENT_BALANCE_REGEX
                .find(body)
                ?.groupValues
                ?.get(1)
                ?.toAmount() ?: return null
        val resolvedBank = SenderNameResolver.bankNameFor(sender, body)
        return BalanceStatement(
            balance = balance,
            accountLast4 = extractAccountLast4(body),
            bankName = resolvedBank?.takeIf { SenderNameResolver.isPlausibleIssuer(it, body) },
            accountType = detectAccountType(body),
        )
    }

    /**
     * True for statement / bill notices ("Statement is sent to ...",
     * "E-statement ... has been mailed", "Statement is generated") — these
     * must never yield a transaction, from the parser OR from rule extracts.
     */
    fun isStatementNotice(body: String): Boolean = STATEMENT_NOTICE_REGEX.containsMatchIn(body)

    /**
     * ISO currency code when the transaction amount is denominated in a
     * foreign currency ("Spent USD 40.95"); null for INR/₹/Rs bodies.
     * Callers persist this alongside the amount so a USD spend is never
     * silently summed as INR.
     */
    fun foreignCurrency(body: String): String? {
        val effectiveBody = STATEMENT_NOTICE_REGEX.replace(body, " ")
        val balanceMatch = BALANCE_REGEX.find(effectiveBody)
        val excluded =
            listOfNotNull(balanceMatch?.range) + AVAILABLE_LIMIT_REGEX.findAll(effectiveBody).map { it.range }
        val hasDomestic =
            AMOUNT_REGEX.findAll(effectiveBody).any { match -> excluded.none { match.range.first in it } }
        if (hasDomestic) return null
        return FOREIGN_AMOUNT_REGEX
            .find(effectiveBody)
            ?.groupValues
            ?.get(1)
            ?.uppercase()
    }

    /**
     * Picks the earlier of the first debit / first credit keyword occurrence.
     * Future / conditional phrasings ("will be deducted", "shall be
     * charged") describe money that has NOT moved yet — an upcoming premium
     * or standing instruction is a reminder, never a transaction — so those
     * matches are skipped entirely.
     */
    private fun detectType(body: String): TransactionType? {
        val debitAt = firstCompletedMatch(body, DEBIT_KEYWORDS)
        val creditAt = firstCompletedMatch(body, CREDIT_KEYWORDS)
        return when {
            debitAt == null && creditAt == null -> null
            debitAt == null -> TransactionType.CREDIT
            creditAt == null -> TransactionType.DEBIT
            debitAt <= creditAt -> TransactionType.DEBIT
            else -> TransactionType.CREDIT
        }
    }

    /** First keyword match not preceded by future-tense "will/shall/would be". */
    private fun firstCompletedMatch(
        body: String,
        keywords: Regex,
    ): Int? =
        keywords
            .findAll(body)
            .firstOrNull { match ->
                val start = maxOf(0, match.range.first - FUTURE_LOOKBEHIND)
                !FUTURE_TENSE_REGEX.containsMatchIn(body.substring(start, match.range.first))
            }?.range
            ?.first

    private fun detectAccountType(body: String): AccountType =
        when {
            // Money spent FROM a wallet ("from Reimbursement Wallet linked to
            // your Pluxee Card") is a wallet transaction even though a card
            // number is quoted — the card is just the wallet's plastic.
            WALLET_SOURCE_REGEX.containsMatchIn(body) -> AccountType.WALLET
            CREDIT_CARD_REGEX.containsMatchIn(body) -> AccountType.CREDIT_CARD
            WALLET_REGEX.containsMatchIn(body) -> AccountType.WALLET
            else -> AccountType.SAVINGS
        }

    /**
     * Masked-card tail. Grouped formats ("4315-81XX-XXXX-4001") must yield
     * the LAST group — the generic account regex would capture the first
     * four digits (the BIN), attributing the payment to a card that does
     * not exist.
     */
    private fun extractAccountLast4(body: String): String? =
        GROUPED_CARD_REGEX.find(body)?.groupValues?.get(1)
            ?: ACCOUNT_REGEX.find(body)?.groupValues?.get(1)
            ?: BANK_MASKED_REGEX.find(body)?.groupValues?.get(1)

    private fun extractMerchant(body: String): String? {
        for (match in MERCHANT_REGEX.findAll(body)) {
            var candidate = match.groupValues[1].trim()
            // Never capture from a URL: "pay in advance now at https://..."
            // is a link, not a merchant.
            if (URL_START_REGEX.containsMatchIn(candidate)) continue
            candidate = candidate.removePrefix("VPA ").removePrefix("vpa ").trim()
            // Cut trailing narration like "on 12-07-26", "Ref 12345" or "via UPI".
            candidate =
                MERCHANT_STOP_REGEX
                    .split(candidate)
                    .first()
                    .trim()
                    .trimEnd('.', ',', ':', ';', '-')
            if (candidate.isEmpty()) continue
            // "to your a/c XX1234" is an account transfer, not a merchant name.
            if (NON_MERCHANT_START_REGEX.containsMatchIn(candidate)) continue
            return candidate
        }
        return standaloneMerchantLine(body) ?: infoDescriptor(body)
    }

    /**
     * Merchant from its own line in the multi-line card-spend shape:
     * "Spent <CUR> <amt> / <Bank> Card no. XX#### / <timestamp> /
     * <MERCHANT>" — no preposition ever precedes the merchant there. The
     * line is stripped of card-network noise ("UBER * PEND" -> "Uber").
     */
    private fun standaloneMerchantLine(body: String): String? {
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 3) return null
        if (lines.none { SPENT_LINE_REGEX.containsMatchIn(it) }) return null
        if (lines.none { CARD_LINE_REGEX.containsMatchIn(it) }) return null
        val candidate =
            lines.firstOrNull { line ->
                !SPENT_LINE_REGEX.containsMatchIn(line) &&
                    !CARD_LINE_REGEX.containsMatchIn(line) &&
                    !TIMESTAMP_LINE_REGEX.containsMatchIn(line) &&
                    !STATE_LINE_REGEX.containsMatchIn(line) &&
                    line.any { it.isLetter() }
            } ?: return null
        return cleanCardNetworkNoise(candidate)
    }

    /**
     * Strips card-network noise from a merchant line: everything after the
     * first "*" separator plus trailing status tokens (PEND / PENDING), then
     * normalizes SHOUTING-CASE to a readable name ("UBER * PEND" -> "Uber").
     */
    private fun cleanCardNetworkNoise(line: String): String? {
        var name = line.substringBefore('*').trim()
        name = TRAILING_STATUS_REGEX.replace(name, "").trim().trimEnd('.', ',', '-')
        if (name.isEmpty() || name.none { it.isLetter() }) return null
        return if (name == name.uppercase()) {
            name
                .lowercase()
                .split(' ')
                .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
        } else {
            name
        }
    }

    /**
     * Human descriptor from an "Info:" narration field — "Info:
     * XXXXXXXXXX6894- RD Installment-Jul 2026" -> "RD Installment". Used
     * only when no real merchant exists: a deposit installment titled "RD
     * Installment" beats a title showing the bank's own name.
     */
    private fun infoDescriptor(body: String): String? {
        val raw =
            INFO_REGEX
                .find(body)
                ?.groupValues
                ?.get(1)
                ?.trim() ?: return null
        return normalizeMerchantCandidate(raw)
    }

    /**
     * Normalizes a raw merchant/narration capture into a human title, or
     * null when nothing presentable remains. Applied to EVERY rule-supplied
     * merchant extract as well as the parser's own "Info:" narration, so a
     * raw capture like "XXXXXXXXXX6894- RD Installment-Jul 2026" always
     * becomes "RD Installment" — regardless of whether a bundled rule or the
     * parser produced it. Clean names ("Uber", "HDFC Flexi Cap Fund") pass
     * through unchanged.
     */
    fun normalizeMerchantCandidate(raw: String): String? {
        var descriptor = LEADING_REFERENCE_REGEX.replace(raw.trim(), "").trim()
        descriptor =
            TRAILING_MONTH_YEAR_REGEX
                .replace(descriptor, "")
                .trim()
                .trimEnd('-', ',', ':', ';')
                .trim()
        if (descriptor.length < 2 || !descriptor.first().isLetter()) return null
        // A leftover long digit run means the field was a reference, not a
        // description — never surface that as a title.
        if (LONG_DIGIT_RUN_REGEX.containsMatchIn(descriptor)) return null
        return descriptor
    }

    private fun categorize(
        merchant: String?,
        body: String,
    ): MerchantCategory {
        val haystack = "${merchant.orEmpty()} $body".lowercase()
        for ((regex, category) in CATEGORY_RULES) {
            if (regex.containsMatchIn(haystack)) return category
        }
        val looksLikeP2p = merchant?.contains('@') == true || Regex("(?i)\\b(?:upi|vpa)\\b").containsMatchIn(body)
        return if (looksLikeP2p) MerchantCategory.TRANSFER else MerchantCategory.OTHER
    }

    private fun String.toAmount(): Double? = replace(",", "").toDoubleOrNull()

    private companion object {
        val AMOUNT_REGEX = Regex("(?i)(?:INR|Rs\\.?|\\u20b9)\\s*([\\d,]+(?:\\.\\d{1,2})?)")

        /**
         * Foreign-currency spend ("Spent USD 40.95"). Only consulted when no
         * domestic amount exists outside balance/limit phrases, so an INR
         * body can never be re-denominated.
         */
        val FOREIGN_AMOUNT_REGEX =
            Regex("(?i)\\b(?:spent|paid|debited)\\s+(USD|EUR|GBP|AED|SGD|AUD|CAD|CHF|JPY|NZD|HKD)\\s*([\\d,]+(?:\\.\\d{1,2})?)")

        /** "Avl Limit: INR 286368.5" / "Avl Lmt INR 98,701.00" — credit headroom, not an amount. */
        val AVAILABLE_LIMIT_REGEX =
            Regex("(?i)av(?:l|bl|ailable)?\\.?\\s*(?:lmt|limit)\\s*:?\\s*(?:INR|Rs\\.?|\\u20b9)\\s*[\\d,]+(?:\\.\\d{1,2})?")

        val DEBIT_KEYWORDS = Regex("(?i)\\b(?:debited|spent|paid|withdrawn|deducted|purchase(?:d)?|sent)\\b")
        val CREDIT_KEYWORDS = Regex("(?i)\\b(?:credited|received|deposited|refund(?:ed)?)\\b")

        /** Future/conditional tense directly before a debit/credit keyword. */
        val FUTURE_TENSE_REGEX = Regex("(?i)\\b(?:will|shall|would)\\s+be\\s*$")

        /** How far back to look for the future-tense phrase ("will be auto-"). */
        const val FUTURE_LOOKBEHIND = 20

        /**
         * Statement / bill notices: the statement's own delivery verbs
         * ("sent", "generated", "mailed") must never count as transaction
         * verbs. Matched spans are scrubbed before parsing.
         */
        val STATEMENT_NOTICE_REGEX =
            Regex(
                "(?i)\\b(?:e-?)?statement\\s+(?:is|has\\s+been|was)\\s+" +
                    "(?:sent|generated|mailed|e-?mailed|dispatched)|" +
                    "\\b(?:e-?)?statement\\s+of\\b[^\\n]{0,80}?\\bhas\\s+been\\s+(?:sent|mailed|e-?mailed)|" +
                    "\\b(?:e-?)?statement\\s+(?:is\\s+)?(?:now\\s+)?(?:available|ready)\\b",
            )

        val ACCOUNT_REGEX =
            Regex(
                "(?i)(?:a/c|a\\\\c|acct|account|card)\\s*(?:no\\.?|number)?\\s*(?:ending\\s*)?(?:in\\s+|with\\s+)?[Xx*]*(\\d{3,4})(?!\\d)",
            )

        /**
         * Grouped masked card number "4315-81XX-XXXX-4001": the LAST group is
         * the card's tail; the first is the BIN.
         */
        val GROUPED_CARD_REGEX =
            Regex("(?<!\\d)\\d{4}[- ][\\dXx*]{2,4}[- ][\\dXx*]{2,4}[- ][Xx*]*(\\d{4})(?!\\d)")

        /**
         * "debited from HDFC Bank XX8709" — a masked tail right after the
         * bank name, with no a/c or card keyword. Lowest-priority fallback.
         */
        val BANK_MASKED_REGEX = Regex("(?i)\\bbank\\s+[Xx*]{2,}(\\d{3,4})(?!\\d)")

        val BALANCE_REGEX =
            Regex(
                "(?i)(?:avl|avbl|avail(?:able)?)\\.?\\s*bal(?:ance)?\\.?" +
                    "(?:\\s+(?:in|for)\\s+(?:your\\s+)?a/c\\s*(?:no\\.?)?\\s*[Xx*]*\\d+)?" +
                    "\\s*(?:is|:|=)?\\s*(?:INR|Rs\\.?|\\u20b9)\\s*([\\d,]+(?:\\.\\d{1,2})?)",
            )

        /**
         * Balance-statement shapes for balance-ONLY messages (consulted only
         * when no transaction parses): "Available Bal in <Bank> A/c XX####
         * as on yesterday:<date> is INR <amt>", "Avl Bal: Rs X", "Available
         * Balance is Rs X", "A/C Bal is INR X", "Bal as on <date>: Rs X" and
         * "Yesterday's bal:INR X" (low-balance alerts). The bounded 60-char
         * span absorbs an intervening bank name, masked account and date
         * without unbounded scanning.
         */
        val STATEMENT_BALANCE_REGEX =
            Regex(
                "(?i)\\b(?:(?:avl|avbl|avail(?:able)?)\\.?\\s*bal(?:ance)?|" +
                    "(?:a/c|acct|account)\\s+bal(?:ance)?|" +
                    "bal(?:ance)?\\s+as\\s+on|" +
                    "yesterday'?s\\s+bal(?:ance)?)\\b" +
                    "[\\s\\S]{0,60}?" +
                    "(?:INR|Rs\\.?|\\u20b9)\\s*([\\d,]+(?:\\.\\d{1,2})?)",
            )

        val REFERENCE_REGEX =
            Regex(
                "(?i)\\bref(?:erence)?\\s*(?:no|num|number|id)?\\.?\\s*[:.]?\\s*([A-Za-z0-9]{6,22})|\\b(?:txn|utr)\\s*(?:id|no)?\\.?\\s*[:.]?\\s*([A-Za-z0-9]{6,22})",
            )

        val MERCHANT_REGEX = Regex("(?i)\\b(?:to|at|towards)\\s+((?:[A-Za-z][A-Za-z0-9@._&'*-]*)(?:\\s+[A-Za-z0-9@._&'*-]+){0,3})")

        /** A candidate that is (the start of) a URL — never a merchant. */
        val URL_START_REGEX = Regex("(?i)^(?:https?\\b|www\\.)")

        /** Words that end a merchant name and start trailing narration. */
        val MERCHANT_STOP_REGEX = Regex("(?i)\\s+(?:on|via|using|from|ref|refno|txn|utr|avl|avbl|info|not\\b|dt|is|was)\\b.*")

        /** Candidates starting with these are account transfers, not merchants. */
        val NON_MERCHANT_START_REGEX = Regex("(?i)^(?:your|ur|the|a/c|ac\\b|acct|account|bank|no\\b)")

        // region standalone merchant line (multi-line card-spend shape)

        /** "Spent USD 40.95" / "Spent Rs. 1,299.00" header line. */
        val SPENT_LINE_REGEX = Regex("(?i)^spent\\s+(?:[A-Z]{3}|Rs\\.?|INR|\\u20b9)\\s*[\\d,]")

        /** "<Bank> Card no. XX5106" line. */
        val CARD_LINE_REGEX = Regex("(?i)card\\s+no\\.?\\s*[Xx*]*\\d")

        /** "20-07-26 07:40:29 IST" timestamp line. */
        val TIMESTAMP_LINE_REGEX = Regex("^\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}\\b")

        /** Balance / limit / advisory lines, never the merchant. */
        val STATE_LINE_REGEX = Regex("(?i)^(?:avl|avbl|available|bal|limit|not\\s+you|sms\\s+block|call\\b|dial\\b)")

        /** Trailing card-network status tokens on a merchant line. */
        val TRAILING_STATUS_REGEX = Regex("(?i)\\s+(?:PEND(?:ING)?|POS|ECOM)\\s*$")

        // endregion

        /** "Info: <narration>" field (HDFC-style), up to the sentence end. */
        val INFO_REGEX = Regex("(?i)\\bInfo\\s*[:.]\\s*([^\\n.]{2,80})")

        /** Leading masked reference in an Info narration ("XXXXXXXXXX6894- "). */
        val LEADING_REFERENCE_REGEX = Regex("^[Xx*]*\\d+\\s*-\\s*")

        /** Trailing "-Jul 2026" style period suffix on an Info descriptor. */
        val TRAILING_MONTH_YEAR_REGEX =
            Regex("(?i)[-\\s]+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]{0,6}\\.?\\s*\\d{2,4}\\s*$")

        /** Digit runs long enough to be a reference, not a description. */
        val LONG_DIGIT_RUN_REGEX = Regex("\\d{5,}")

        val CREDIT_CARD_REGEX = Regex("(?i)credit\\s*card|\\bcard\\s+(?:no\\.?|number|ending|[Xx*]*\\d{3,4})")
        val WALLET_REGEX = Regex("(?i)\\bwallet\\b")

        /** Money moving FROM a wallet, or a wallet that merely fronts a card. */
        val WALLET_SOURCE_REGEX = Regex("(?i)\\bfrom\\b[^\\n]{0,40}?\\bwallet\\b|\\bwallet\\s+linked\\b")

        val CATEGORY_RULES =
            listOf(
                Regex("(?i)swiggy|zomato") to MerchantCategory.FOOD,
                Regex("(?i)amazon|flipkart|myntra") to MerchantCategory.SHOPPING,
                Regex("(?i)\\buber\\b|\\bola\\b|irctc") to MerchantCategory.TRANSPORTATION,
                Regex("(?i)makemytrip|hotel") to MerchantCategory.TRAVEL_HOTEL,
                Regex("(?i)netflix|bookmyshow|spotify") to MerchantCategory.ENTERTAINMENT,
                Regex("(?i)\\bschool\\b|\\bfees?\\b|tuition") to MerchantCategory.EDUCATION,
                Regex("(?i)hospital|pharmacy|apollo") to MerchantCategory.HOSPITAL,
                Regex("(?i)electricity|\\bwater\\b|\\bgas\\b|broadband") to MerchantCategory.UTILITY_BILL,
                // RD/FD installments are deposit CONTRIBUTIONS, not purchases.
                Regex("(?i)\\bsip\\b|mutual\\s*fund|zerodha|groww|\\brd\\s+instal?lment|\\bfd\\s+instal?lment|recurring\\s+deposit") to
                    MerchantCategory.INVESTMENT,
                // NPS contributions are retirement investments; PRAN is the
                // NPS account identifier and only appears in that context.
                Regex("(?i)\\bnps\\b|\\bpran\\b") to MerchantCategory.INVESTMENT,
                Regex("(?i)\\brecharged?\\b") to MerchantCategory.RECHARGE,
            )
    }
}

/**
 * A standalone account-balance statement: the reported balance plus the
 * account it belongs to. Deliberately NOT a [ParsedTransaction] — no money
 * moved, so it must never create a transaction row; it only refreshes the
 * account's last known balance.
 */
data class BalanceStatement(
    val balance: Double,
    val accountLast4: String?,
    /** Plausible-issuer names only; null when the sender is not an issuer. */
    val bankName: String?,
    val accountType: AccountType,
)
