package app.clearsms.domain.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit contract of [TransactionParser.normalizeMerchantCandidate] — the single
 * cleanup applied to every rule-supplied merchant extract and the parser's own
 * "Info:" narration, so raw narration captures can never ship as titles.
 */
class MerchantNormalizationTest {
    private val parser = TransactionParser()

    @Test
    fun `strips leading masked reference and trailing month-year`() {
        assertThat(parser.normalizeMerchantCandidate("XXXXXXXXXX6894- RD Installment-Jul 2026"))
            .isEqualTo("RD Installment")
    }

    @Test
    fun `strips trailing month-year only`() {
        assertThat(parser.normalizeMerchantCandidate("Salary Jan 2026")).isEqualTo("Salary")
    }

    @Test
    fun `clean names pass through unchanged`() {
        assertThat(parser.normalizeMerchantCandidate("Uber")).isEqualTo("Uber")
        assertThat(parser.normalizeMerchantCandidate("HDFC Flexi Cap Fund")).isEqualTo("HDFC Flexi Cap Fund")
        assertThat(parser.normalizeMerchantCandidate("Prepaid Recharge")).isEqualTo("Prepaid Recharge")
    }

    @Test
    fun `pure reference captures are rejected`() {
        assertThat(parser.normalizeMerchantCandidate("XXXXXXXXXX6894-")).isNull()
        assertThat(parser.normalizeMerchantCandidate("UPI-519876543210")).isNull()
    }

    @Test
    fun `leftover long digit runs are rejected`() {
        assertThat(parser.normalizeMerchantCandidate("Order 7481038541423177728")).isNull()
    }

    @Test
    fun `short digits inside a name survive`() {
        assertThat(parser.normalizeMerchantCandidate("Store 24")).isEqualTo("Store 24")
    }
}
