package app.clearsms.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CurrencyFormatTest {
    @Test
    fun `uses indian digit grouping`() {
        assertThat(CurrencyFormat.rupees(123456.78)).isEqualTo("₹1,23,456.78")
        assertThat(CurrencyFormat.rupees(1000.0)).isEqualTo("₹1,000")
        assertThat(CurrencyFormat.rupees(10000000.0)).isEqualTo("₹1,00,00,000")
    }

    @Test
    fun `negative amounts carry a leading minus`() {
        assertThat(CurrencyFormat.rupees(-500.5)).isEqualTo("-₹500.5")
    }

    @Test
    fun `signed form uses explicit plus and minus signs`() {
        assertThat(CurrencyFormat.signedRupees(12000.0, positive = true)).isEqualTo("+₹12,000")
        assertThat(CurrencyFormat.signedRupees(12000.0, positive = false)).isEqualTo("−₹12,000")
        // The magnitude is always absolute; the sign comes from the flag.
        assertThat(CurrencyFormat.signedRupees(-450.0, positive = false)).isEqualTo("−₹450")
    }
}
