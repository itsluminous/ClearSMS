package app.clearsms.domain.model

/** Raw fields extracted from a transaction SMS before persistence. */
data class ParsedTransaction(
    val amount: Double,
    val type: TransactionType,
    val merchantName: String? = null,
    val accountLast4: String? = null,
    val bankName: String? = null,
    val balance: Double? = null,
    val referenceNumber: String? = null,
    val merchantCategory: MerchantCategory = MerchantCategory.OTHER,
    val accountType: AccountType = AccountType.SAVINGS,
)
