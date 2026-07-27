package app.clearsms.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AmountKindTest {
    @Test
    fun `debit type maps to DEBIT`() {
        assertThat(amountKindOf(mapOf("amount" to "500.0", "type" to "debit")))
            .isEqualTo(AmountKind.DEBIT)
    }

    @Test
    fun `credit type maps to CREDIT`() {
        assertThat(amountKindOf(mapOf("amount" to "500.0", "type" to "credit")))
            .isEqualTo(AmountKind.CREDIT)
    }

    @Test
    fun `balance with no transaction type maps to BALANCE`() {
        assertThat(amountKindOf(mapOf("balance" to "10234.55")))
            .isEqualTo(AmountKind.BALANCE)
    }

    @Test
    fun `explicit type wins even when a balance is also present`() {
        assertThat(amountKindOf(mapOf("amount" to "500.0", "type" to "debit", "balance" to "10234.55")))
            .isEqualTo(AmountKind.DEBIT)
    }

    @Test
    fun `no type and no balance has no amount kind`() {
        assertThat(amountKindOf(mapOf("otp_code" to "123456"))).isNull()
        assertThat(amountKindOf(emptyMap())).isNull()
    }
}
