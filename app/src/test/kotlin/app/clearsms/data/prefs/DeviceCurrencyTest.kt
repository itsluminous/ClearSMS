package app.clearsms.data.prefs

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

/** SIM country first, device locale second, the historic rupee default last. */
class DeviceCurrencyTest {
    @Test
    fun `the SIM's country decides before the device locale`() {
        // A Chilean SIM in a phone whose UI language is US English: pesos.
        assertThat(DeviceCurrency.resolve("CL", Locale.US)).isEqualTo("CLP")
        assertThat(DeviceCurrency.resolve("IN", Locale.US)).isEqualTo("INR")
        assertThat(DeviceCurrency.resolve("JP", Locale.US)).isEqualTo("JPY")
    }

    @Test
    fun `without a SIM the device locale decides`() {
        assertThat(DeviceCurrency.resolve(null, Locale("es", "CL"))).isEqualTo("CLP")
        assertThat(DeviceCurrency.resolve("", Locale.US)).isEqualTo("USD")
        assertThat(DeviceCurrency.resolve(null, Locale("en", "IN"))).isEqualTo("INR")
    }

    @Test
    fun `no region anywhere keeps the historic rupee default`() {
        assertThat(DeviceCurrency.resolve(null, Locale.ENGLISH)).isEqualTo("INR")
        assertThat(DeviceCurrency.resolve("XX", Locale.ROOT)).isEqualTo("INR")
        assertThat(DeviceCurrency.currencyOfRegion("AQ")).isNull()
    }
}
