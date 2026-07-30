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
        // A FAILED payment moved no money. Its narration routinely carries
        // completed-tense verbs ("done", "if debited") that satisfy the
        // debit heuristics, so failure language rejects the whole message
        // up front — no transaction row, ever. Refund credits arrive as
        // their own later message and parse on their own.
        if (isFailedPayment(body)) return null
        // A statement / bill notice ("Statement is sent...", "E-statement of
        // ... has been mailed") reports money OWED, not money moved. Its
        // verbs ("sent", "generated") and its "Total of Rs X ... is due"
        // amounts satisfy the transaction heuristics, so the notice phrases
        // are scrubbed BEFORE parsing: what remains carries no completed
        // debit/credit verb and the message stays a reminder only.
        val effectiveBody = GuardLibrary.scrub(GuardId.STATEMENT_NOTICE, body)
        // "Payment of INR X ... is due (on <date>)" announces a FUTURE
        // obligation — a bill reminder, never a completed debit. The trailing
        // "Ignore if paid" advisory carries a completed-tense verb ("paid")
        // that satisfies the debit heuristics, so the whole notice is
        // rejected up front; the reminder pipeline extracts the total /
        // minimum due and due date instead.
        if (GuardLibrary.matches(GuardId.BILL_DUE_NOTICE, effectiveBody)) return null
        val type = detectType(effectiveBody) ?: return null

        val balanceMatch = BALANCE_REGEX.find(effectiveBody)
        val balance = balanceMatch?.groupValues?.get(1)?.toAmount()

        // Amounts inside the balance or an "Avl Limit/Lmt" phrase are state,
        // not the transaction; excluding them keeps "Avl Limit: INR 286368.5"
        // from becoming the amount of a foreign-currency card spend.
        val availableLimitMatch = AVAILABLE_LIMIT_REGEX.findAll(effectiveBody).toList()
        val availableLimit =
            availableLimitMatch
                .firstOrNull()
                ?.groupValues
                ?.get(1)
                ?.toAmount()
        val excluded =
            listOfNotNull(balanceMatch?.range) + availableLimitMatch.map { it.range }
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
            availableLimit = availableLimit,
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
     * Parses a TOTAL credit-limit statement — the issuer confirming what the
     * card's overall limit now is ("The credit limit for your ... Credit
     * Card 1234X5678 has been changed from INR 100000 to INR 150000",
     * "Credit Limit Increased! ... Your new limit is ₹150000", "Total
     * Credit Limit: Rs.150000"). Only CONFIRMED statements qualify: limit
     * increase OFFERS ("eligible for", "pre-approved", "can be increased
     * to") describe money the user does not have yet and are rejected, as
     * are loan/telecom "limit" messages (the body must mention a card).
     */
    fun parseTotalLimit(
        sender: String,
        body: String,
    ): TotalLimitStatement? {
        if (!CARD_CONTEXT_REGEX.containsMatchIn(body)) return null
        if (GuardLibrary.matches(GuardId.LIMIT_OFFER, body)) return null
        val limit =
            TOTAL_LIMIT_CHANGED_REGEX
                .find(body)
                ?.groupValues
                ?.get(1)
                ?: TOTAL_LIMIT_NEW_REGEX
                    .find(body)
                    ?.takeIf { !LAKH_SUFFIX_REGEX.containsMatchIn(body.substring(it.range.last + 1)) }
                    ?.groupValues
                    ?.get(1)
                ?: TOTAL_LIMIT_STATED_REGEX
                    .find(body)
                    ?.groupValues
                    ?.get(1)
        val amount = limit?.toAmount() ?: return null
        if (amount <= 0.0) return null
        val resolvedBank = SenderNameResolver.bankNameFor(sender, body)
        return TotalLimitStatement(
            totalLimit = amount,
            accountLast4 = extractCardTail(body),
            bankName = resolvedBank?.takeIf { SenderNameResolver.isPlausibleIssuer(it, body) },
        )
    }

    /**
     * Masked-card tail for limit statements. The inline-masked shape
     * "Credit Card 4375X9012" (BIN, mask char, tail — no separators) must
     * yield the LAST digit group; the generic account regex would capture
     * the BIN. Falls back to the shared tail extraction otherwise.
     */
    private fun extractCardTail(body: String): String? =
        INLINE_MASKED_CARD_REGEX.find(body)?.groupValues?.get(1)
            ?: extractAccountLast4(body)

    /**
     * True for statement / bill notices ("Statement is sent to ...",
     * "E-statement ... has been mailed", "Statement is generated") and for
     * bill-due notices ("Payment of INR X ... is due on <date>") — these
     * report money OWED, not money moved, so they must never yield a
     * transaction, from the parser OR from rule extracts.
     */
    fun isStatementNotice(body: String): Boolean =
        GuardLibrary.matches(GuardId.STATEMENT_NOTICE, body) || GuardLibrary.matches(GuardId.BILL_DUE_NOTICE, body)

    /**
     * True when the body reports a FAILED / declined / unsuccessful payment.
     * No money moved, so such a message must never yield a transaction —
     * from the parser OR from rule extracts. (If the amount was provisionally
     * debited, the refund arrives as its own message and parses then.)
     */
    fun isFailedPayment(body: String): Boolean = GuardLibrary.matches(GuardId.FAILED_PAYMENT, body)

    /**
     * ISO currency code when the transaction amount is denominated in a
     * foreign currency ("Spent USD 40.95"); null for INR/₹/Rs bodies.
     * Callers persist this alongside the amount so a USD spend is never
     * silently summed as INR.
     */
    fun foreignCurrency(body: String): String? {
        val effectiveBody = GuardLibrary.scrub(GuardId.STATEMENT_NOTICE, body)
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
        if (debitAt == null && creditAt == null) return verblessDebit(body)
        return when {
            debitAt == null -> TransactionType.CREDIT
            creditAt == null -> TransactionType.DEBIT
            debitAt <= creditAt -> TransactionType.DEBIT
            else -> TransactionType.CREDIT
        }
    }

    /**
     * Two real notification shapes carry NO debit/credit verb at all:
     *
     * 1. The card-network template "Txn Rs.X / On <Bank> Card <n> / At
     *    <merchant/VPA>". Issuers only send this shape for OUTGOING
     *    authorizations — incoming money always announces itself with an
     *    explicit verb ("credited", "refund", "payment received") — so a
     *    verbless card Txn at a merchant is money out: DEBIT.
     * 2. A biller confirming "payment of Rs.X ... successful/done/completed":
     *    the user paid out. (Payments RECEIVED say "received", an explicit
     *    credit verb, and never reach here.)
     */
    private fun verblessDebit(body: String): TransactionType? =
        when {
            CARD_TXN_HEADER_REGEX.containsMatchIn(body) &&
                CREDIT_CARD_REGEX.containsMatchIn(body) &&
                TXN_AT_MERCHANT_REGEX.containsMatchIn(body) -> TransactionType.DEBIT
            PAYMENT_DONE_REGEX.containsMatchIn(body) -> TransactionType.DEBIT
            else -> null
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
                !GuardLibrary.matches(GuardId.FUTURE_TENSE, body.substring(start, match.range.first))
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
            // "Click <url> to know the transaction status": a "to"/"at" that
            // directly follows a link introduces an instruction, never a
            // merchant. Guard both ways — a URL right before the preposition,
            // and a candidate that starts with an instruction verb.
            val precedingWindow = body.substring(maxOf(0, match.range.first - URL_LOOKBEHIND), match.range.first)
            if (PRECEDING_URL_REGEX.containsMatchIn(precedingWindow)) continue
            if (GuardLibrary.matches(GuardId.INSTRUCTION_START, candidate)) continue
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
     * Merchant from its own segment in the multi-line card-spend shape:
     * "Spent <CUR> <amt> / <Bank> Card no. XX#### / <timestamp> /
     * <MERCHANT>" — no preposition ever precedes the merchant there.
     * Segments are separated by newlines OR by a "/" adjacent to whitespace
     * (both delivery formats exist in the wild); a slash embedded in a date
     * or phone number ("12/07/26", "18002586161/SMS") never splits.
     */
    private fun standaloneMerchantLine(body: String): String? {
        val lines =
            body
                .lines()
                .flatMap { it.split(SEGMENT_SPLIT_REGEX) }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
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
     * Cleans a card-network merchant line. The "*" separates the payment
     * aggregator/processor prefix from what follows:
     *
     * - "PTM*ZOMATO" / "RAZ*Zomato": a REAL merchant follows the star. The
     *   token is kept WHOLE, verbatim — the star is a separator, never a
     *   truncation boundary. (The part after the star is the meaningful
     *   merchant; the whole token is kept because it is what the statement
     *   shows and what the user asked to see.)
     * - "UBER * PEND": a STATUS tail (PEND/PENDING/POS/ECOM) follows the
     *   star — noise, stripped; the merchant is the part before it,
     *   normalized from SHOUTING-CASE ("UBER * PEND" -> "Uber").
     */
    private fun cleanCardNetworkNoise(line: String): String? {
        val trimmed = TRAILING_STATUS_REGEX.replace(line.trim(), "").trim().trimEnd('.', ',', '-')
        val star = trimmed.indexOf('*')
        if (star >= 0) {
            val after = trimmed.substring(star + 1).trim()
            if (after.any { it.isLetter() } && !STATUS_TOKEN_REGEX.matches(after)) {
                return trimmed
            }
        }
        var name = trimmed.substringBefore('*').trim()
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
        for ((regex, category) in ParserTables.merchantCategories) {
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

        /**
         * "Avl Limit: INR 286368.5" / "Avl Lmt INR 98,701.00" / "Available
         * Credit Limit is Rs.40,000" — credit headroom, not an amount. The
         * captured figure feeds [ParsedTransaction.availableLimit]; the
         * matched span is also excluded from amount detection. Phrasings per
         * the audited device corpora: avl/avbl/available, optional "credit",
         * limit/lmt, optional ":"/"is".
         */
        val AVAILABLE_LIMIT_REGEX =
            Regex(
                "(?i)av(?:l|bl|ailable)?\\.?\\s*(?:credit\\s+)?(?:lmt|limit)\\s*:?\\s*(?:is\\s+)?" +
                    "(?:INR|Rs\\.?|\\u20b9)\\s*([\\d,]+(?:\\.\\d{1,2})?)",
            )

        // region total credit limit statements

        /** Total-limit statements only make sense for cards, never loans/telecom. */
        val CARD_CONTEXT_REGEX = Regex("(?i)\\bcard\\b")

        /**
         * Limit-increase OFFERS: money the user does not have yet. "eligible
         * for a Credit Limit increase", "pre-approved ... Credit limit:
         * Rs.X", "limit can be increased to Rs X" must never set the total.
         */
        val LIMIT_OFFER_REGEX =
            Regex("(?i)\\b(?:eligible|pre-?approved|can\\s+be\\s+(?:increased|enhanced)|to\\s+avail|avail\\s+now|apply\\s+now)\\b")

        /**
         * "credit limit for your ... Card ... has been changed from INR X to
         * INR Y" — the NEW total is the second amount (after "to").
         */
        val TOTAL_LIMIT_CHANGED_REGEX =
            Regex(
                "(?i)credit\\s+limit\\b[^\\n]{0,80}?\\bchanged\\s+from\\s+" +
                    "(?:INR|Rs\\.?|\\u20b9)\\s*[\\d,]+(?:\\.\\d{1,2})?\\s+to\\s+" +
                    "(?:INR|Rs\\.?|\\u20b9)\\s*([\\d,]+(?:\\.\\d{1,2})?)",
            )

        /**
         * "Your new limit is ₹150000" after a processed enhancement. The
         * currency marker tolerates "?" — a common mojibake of "₹" in real
         * issuer SMS ("Your new limit is ?1500000").
         */
        val TOTAL_LIMIT_NEW_REGEX =
            Regex("(?i)\\b(?:your\\s+)?new\\s+(?:credit\\s+)?limit\\s+is\\s*(?:INR|Rs\\.?|\\u20b9|\\?)\\s*([\\d,]+(?:\\.\\d{1,2})?)")

        /**
         * Direct statements of the total: "Total Credit Limit: Rs.150000",
         * "Total Limit is INR 150000", "Sanctioned Limit of Rs 150000",
         * "your limit of INR 150000".
         */
        val TOTAL_LIMIT_STATED_REGEX =
            Regex(
                "(?i)\\b(?:total\\s+(?:credit\\s+)?limit|sanctioned\\s+limit|your\\s+limit)\\s*" +
                    "(?:is|:|of)?\\s*(?:INR|Rs\\.?|\\u20b9)\\s*([\\d,]+(?:\\.\\d{1,2})?)",
            )

        /** "Rs. XX lacs/lakhs" — a rounded marketing figure, never a card total. */
        val LAKH_SUFFIX_REGEX = Regex("(?i)^\\s*(?:lacs?|lakhs?)\\b")

        /**
         * Inline-masked card number "4375X9012" / "437500XX9012": digits,
         * mask characters, then the real 3-4 digit tail.
         */
        val INLINE_MASKED_CARD_REGEX = Regex("(?<!\\d)\\d{2,6}[Xx*]+(\\d{3,4})(?![\\dXx*])")

        // endregion

        val DEBIT_KEYWORDS = Regex("(?i)\\b(?:debited|spent|paid|withdrawn|deducted|purchase(?:d)?|sent)\\b")
        val CREDIT_KEYWORDS = Regex("(?i)\\b(?:credited|received|deposited|refund(?:ed)?)\\b")

        /**
         * Failure language: a payment that never happened. Anchored to
         * payment nouns or explicit failure verbs so a success confirmation
         * can never trip it.
         */
        val FAILED_PAYMENT_REGEX =
            Regex(
                "(?i)\\bhas\\s+failed\\b|" +
                    "\\b(?:payment|transaction|txn|transfer|recharge)\\s+(?:has\\s+|was\\s+)?failed\\b|" +
                    "\\bcould\\s+not\\s+be\\s+(?:processed|completed)\\b|" +
                    "\\b(?:was\\s+)?declined\\b|" +
                    "\\bunsuccessful\\b",
            )

        /**
         * "Txn Rs.55.00" header of the verbless card-network template — the
         * word "Txn" immediately followed by a currency amount at the start
         * of the message or a line. "txn of Rs X" (OTP narration) has "of"
         * in between and never matches.
         */
        val CARD_TXN_HEADER_REGEX = Regex("(?i)(?:^|\\n)\\s*txn\\s+(?:INR|Rs\\.?|\\u20b9)\\s*[\\d,]")

        /** "At <merchant/VPA>" clause of the card-network Txn template. */
        val TXN_AT_MERCHANT_REGEX = Regex("(?i)\\bat\\s+\\S")

        /**
         * "payment of Rs.X ... successful/done/completed": a biller
         * confirming the user's outgoing payment. Bounded gap; failure
         * language is rejected before this is ever consulted.
         */
        val PAYMENT_DONE_REGEX =
            Regex(
                "(?i)\\bpayment\\s+of\\s+(?:INR|Rs\\.?|\\u20b9)\\s*[\\d,]+(?:\\.\\d{1,2})?" +
                    "[^\\n]{0,80}?\\b(?:successful|completed|done)\\b",
            )

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

        /**
         * Bill-due notice: "Payment of INR 532.62 for <card/biller> is due
         * on 04-04-26" and "your <biller> bill of Rs.1178.82 for <id> is due
         * on 10-Jun-26" — money the user still OWES. Same class as the
         * statement notices above: never a transaction (the trailing "Ignore
         * if already paid" advisory would otherwise satisfy the debit
         * heuristics). The bounded 100-char gaps absorb the card / biller /
         * consumer-id description between the amount and "is due".
         */
        val BILL_DUE_NOTICE_REGEX =
            Regex(
                "(?i)\\b(?:payment|bill)\\s+of\\s+(?:INR|Rs\\.?|\\u20b9)\\s*[\\d,]+(?:\\.\\d{1,2})?[^\\n]{0,100}?\\bis\\s+due\\b",
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
                "(?i)\\bref(?:erence)?\\s*(?:no|num|number|id)?\\.?\\s*[:.]?\\s*((?=[A-Za-z0-9]*\\d)[A-Za-z0-9]{6,22})|" +
                    "\\b(?:txn|utr|upi)\\s*(?:id|no|ref)?\\.?\\s*[:.]?\\s*((?=[A-Za-z0-9]*\\d)[A-Za-z0-9]{6,22})",
            )

        val MERCHANT_REGEX = Regex("(?i)\\b(?:to|at|towards)\\s+((?:[A-Za-z][A-Za-z0-9@._&'*-]*)(?:\\s+[A-Za-z0-9@._&'*-]+){0,3})")

        /** A candidate that is (the start of) a URL — never a merchant. */
        val URL_START_REGEX = Regex("(?i)^(?:https?\\b|www\\.)")

        /** Words that end a merchant name and start trailing narration. */
        val MERCHANT_STOP_REGEX = Regex("(?i)\\s+(?:on|via|using|from|by|ref|refno|txn|utr|avl|avbl|info|not\\b|dt|is|was)\\b.*")

        /** Candidates starting with these are account transfers, not merchants. */
        val NON_MERCHANT_START_REGEX = Regex("(?i)^(?:your|ur|the|a/c|ac\\b|acct|account|bank|no\\b)")

        /**
         * Instruction verbs: "to know the transaction status", "to avoid
         * late fees" — link/call-to-action phrasing, never a merchant.
         */
        val INSTRUCTION_START_REGEX =
            Regex("(?i)^(?:know|check|view|track|see|get|download|install|update|complete|continue|avoid|claim|apply|visit|click|login)\\b")

        /** A URL directly before the merchant preposition ("Click <url> to ..."). */
        val PRECEDING_URL_REGEX = Regex("(?i)(?:https?://\\S+|www\\.\\S+|\\b[a-z0-9][a-z0-9.-]*\\.[a-z]{2,6}/\\S*)\\s*$")

        /** Chars of context inspected for [PRECEDING_URL_REGEX]. */
        const val URL_LOOKBEHIND = 80

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

        /** A bare status token — noise after "*", never a merchant. */
        val STATUS_TOKEN_REGEX = Regex("(?i)^(?:PEND(?:ING)?|POS|ECOM|AUTH|RATE)$")

        /**
         * Segment separator of the card-spend template: a "/" with
         * whitespace on at least one side. Dates ("12/07/26") and helpline
         * fragments ("18002586161/SMS") keep their slashes.
         */
        val SEGMENT_SPLIT_REGEX = Regex("\\s+/\\s*|\\s*/\\s+")

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

/**
 * An issuer's confirmed statement of a card's TOTAL credit limit ("changed
 * from INR X to INR Y", "Your new limit is ₹Y"). State, not movement: it
 * must never create a transaction, only refresh the card's total limit so
 * outstanding and utilization stay derivable.
 */
data class TotalLimitStatement(
    val totalLimit: Double,
    val accountLast4: String?,
    /** Plausible-issuer names only; null when the sender is not an issuer. */
    val bankName: String?,
)
