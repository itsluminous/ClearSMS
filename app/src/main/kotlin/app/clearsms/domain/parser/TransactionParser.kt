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
        val type = detectType(body) ?: return null

        val balanceMatch = BALANCE_REGEX.find(body)
        val balance = balanceMatch?.groupValues?.get(1)?.toAmount()

        val amount =
            AMOUNT_REGEX
                .findAll(body)
                .firstOrNull { balanceMatch == null || it.range.first !in balanceMatch.range }
                ?.groupValues
                ?.get(1)
                ?.toAmount() ?: return null

        val merchant = extractMerchant(body)
        return ParsedTransaction(
            amount = amount,
            type = type,
            merchantName = merchant,
            accountLast4 = ACCOUNT_REGEX.find(body)?.groupValues?.get(1),
            bankName = SenderNameResolver.bankNameFor(sender, body),
            balance = balance,
            referenceNumber =
                REFERENCE_REGEX.find(body)?.let { match ->
                    match.groupValues[1].ifEmpty { match.groupValues[2] }.ifEmpty { null }
                },
            merchantCategory = categorize(merchant, body),
            accountType = detectAccountType(body),
        )
    }

    /** Picks the earlier of the first debit / first credit keyword occurrence. */
    private fun detectType(body: String): TransactionType? {
        val debitAt = DEBIT_KEYWORDS.find(body)?.range?.first
        val creditAt = CREDIT_KEYWORDS.find(body)?.range?.first
        return when {
            debitAt == null && creditAt == null -> null
            debitAt == null -> TransactionType.CREDIT
            creditAt == null -> TransactionType.DEBIT
            debitAt <= creditAt -> TransactionType.DEBIT
            else -> TransactionType.CREDIT
        }
    }

    private fun detectAccountType(body: String): AccountType =
        when {
            CREDIT_CARD_REGEX.containsMatchIn(body) -> AccountType.CREDIT_CARD
            WALLET_REGEX.containsMatchIn(body) -> AccountType.WALLET
            else -> AccountType.SAVINGS
        }

    private fun extractMerchant(body: String): String? {
        for (match in MERCHANT_REGEX.findAll(body)) {
            var candidate = match.groupValues[1].trim()
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
        return null
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

        val DEBIT_KEYWORDS = Regex("(?i)\\b(?:debited|spent|paid|withdrawn|deducted|purchase(?:d)?|sent)\\b")
        val CREDIT_KEYWORDS = Regex("(?i)\\b(?:credited|received|deposited|refund(?:ed)?)\\b")

        val ACCOUNT_REGEX =
            Regex(
                "(?i)(?:a/c|a\\\\c|acct|account|card)\\s*(?:no\\.?|number)?\\s*(?:ending\\s*)?(?:in\\s+|with\\s+)?[Xx*]*(\\d{3,4})(?!\\d)",
            )

        val BALANCE_REGEX =
            Regex(
                "(?i)(?:avl|avbl|avail(?:able)?)\\.?\\s*bal(?:ance)?\\.?" +
                    "(?:\\s+(?:in|for)\\s+(?:your\\s+)?a/c\\s*(?:no\\.?)?\\s*[Xx*]*\\d+)?" +
                    "\\s*(?:is|:|=)?\\s*(?:INR|Rs\\.?|\\u20b9)\\s*([\\d,]+(?:\\.\\d{1,2})?)",
            )

        val REFERENCE_REGEX =
            Regex(
                "(?i)\\bref(?:erence)?\\s*(?:no|num|number|id)?\\.?\\s*[:.]?\\s*([A-Za-z0-9]{6,22})|\\b(?:txn|utr)\\s*(?:id|no)?\\.?\\s*[:.]?\\s*([A-Za-z0-9]{6,22})",
            )

        val MERCHANT_REGEX = Regex("(?i)\\b(?:to|at|towards)\\s+((?:[A-Za-z][A-Za-z0-9@._&'*-]*)(?:\\s+[A-Za-z0-9@._&'*-]+){0,3})")

        /** Words that end a merchant name and start trailing narration. */
        val MERCHANT_STOP_REGEX = Regex("(?i)\\s+(?:on|via|using|from|ref|refno|txn|utr|avl|avbl|info|not\\b|dt|is|was)\\b.*")

        /** Candidates starting with these are account transfers, not merchants. */
        val NON_MERCHANT_START_REGEX = Regex("(?i)^(?:your|ur|the|a/c|ac\\b|acct|account|bank|no\\b)")

        val CREDIT_CARD_REGEX = Regex("(?i)credit\\s*card|\\bcard\\s+(?:no\\.?|number|ending|[Xx*]*\\d{3,4})")
        val WALLET_REGEX = Regex("(?i)\\bwallet\\b")

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
                Regex("(?i)\\bsip\\b|mutual\\s*fund|zerodha|groww") to MerchantCategory.INVESTMENT,
            )
    }
}
