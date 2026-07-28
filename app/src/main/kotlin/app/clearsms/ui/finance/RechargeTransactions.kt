package app.clearsms.ui.finance

import app.clearsms.data.db.TransactionEntity
import app.clearsms.domain.model.MerchantCategory

/**
 * Identifies prepaid-recharge transactions for the Finance "Recharges" pill.
 *
 * Primary signal is [MerchantCategory.RECHARGE]; the merchant-name fallback
 * covers rows persisted from rule extracts that carry a "recharge" title
 * before the parser/repository learn to stamp the merchant category.
 */
object RechargeTransactions {
    fun isRecharge(tx: TransactionEntity): Boolean =
        tx.category == MerchantCategory.RECHARGE ||
            tx.merchantName?.contains("recharge", ignoreCase = true) == true
}
