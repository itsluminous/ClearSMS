package app.clearsms.sms

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The one SIM-naming formatter: slot first, carrier second, blank-safe. */
class SimLabelTest {
    @Test
    fun `slot and name - slot leads, separated by a spaced dash`() {
        assertThat(SimLabel.slotFirst(1, "Carrier A")).isEqualTo("SIM 1 - Carrier A")
        assertThat(SimLabel.slotFirst(2, "Work")).isEqualTo("SIM 2 - Work")
    }

    @Test
    fun `blank or whitespace name degrades to the bare slot`() {
        assertThat(SimLabel.slotFirst(1, "")).isEqualTo("SIM 1")
        assertThat(SimLabel.slotFirst(2, "   ")).isEqualTo("SIM 2")
        assertThat(SimLabel.nameSuffix("")).isEmpty()
        assertThat(SimLabel.nameSuffix("Carrier A")).isEqualTo(" - Carrier A")
    }

    @Test
    fun `same carrier on two slots yields two distinct labels`() {
        assertThat(SimLabel.slotFirst(1, "Carrier A")).isNotEqualTo(SimLabel.slotFirst(2, "Carrier A"))
    }
}
