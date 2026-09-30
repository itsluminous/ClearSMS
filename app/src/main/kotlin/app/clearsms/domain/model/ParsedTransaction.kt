package app.clearsms.domain.model

/** Raw fields extracted from a transaction SMS before persistence. */
data class ParsedTransaction(
    val amount: Double,
    /**
     * ISO 4217 code the [amount] is denominated in - the currency the message
     * named, else the device/SIM or Settings-override fallback (see
     * [app.clearsms.domain.parser.CurrencyDetector]). Never assumed to be
     * rupees: a Chilean `$1.000` is 1000 CLP and must be stored, shown and
     * totalled as such.
     */
    val currency: String = CurrencyCatalog.INR_CODE,
    val type: TransactionType,
    val merchantName: String? = null,
    val accountLast4: String? = null,
    val bankName: String? = null,
    val balance: Double? = null,
    /** Issuer-reported available credit limit ("Avl Limit: INR ..."), for cards. */
    val availableLimit: Double? = null,
    val referenceNumber: String? = null,
    val merchantCategory: MerchantCategory = MerchantCategory.OTHER,
    val accountType: AccountType = AccountType.SAVINGS,
)
