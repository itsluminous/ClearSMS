package app.clearsms.ui.finance

import app.clearsms.data.db.TransactionEntity
import app.clearsms.domain.model.MerchantCategory
import app.clearsms.domain.model.TransactionType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RechargeTransactionsTest {
    private fun tx(
        category: MerchantCategory = MerchantCategory.OTHER,
        merchantName: String? = null,
    ) = TransactionEntity(
        id = 0,
        amount = 199.0,
        type = TransactionType.DEBIT,
        merchantName = merchantName,
        accountNumber = "",
        bankName = "",
        timestamp = 0L,
        category = category,
        rawSmsId = 0,
    )

    @Test
    fun `recharge merchant category is a recharge`() {
        assertThat(RechargeTransactions.isRecharge(tx(category = MerchantCategory.RECHARGE))).isTrue()
    }

    @Test
    fun `recharge-titled transaction is a recharge regardless of category`() {
        assertThat(RechargeTransactions.isRecharge(tx(merchantName = "Prepaid Recharge"))).isTrue()
    }

    @Test
    fun `ordinary transaction is not a recharge`() {
        assertThat(RechargeTransactions.isRecharge(tx(merchantName = "Some Cafe"))).isFalse()
    }
}
