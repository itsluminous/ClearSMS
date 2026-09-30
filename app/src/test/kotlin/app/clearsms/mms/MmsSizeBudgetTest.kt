package app.clearsms.mms

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The outgoing size budget is the CARRIER's limit, not ours: the
 * compression target is that limit minus a fixed envelope margin, an
 * unknown limit falls back to the AOSP default the platform itself would
 * apply, and a generous carrier is never compressed down to a stricter
 * one's number (issue #51).
 */
class MmsSizeBudgetTest {
    @Test
    fun `a known carrier limit becomes the PDU ceiling and the target is that limit minus the envelope margin`() {
        // The reporter's phone: carrier config at the AOSP default.
        val budget = MmsSizeBudget.forCarrier(307_200)

        assertThat(budget.limitKnown).isTrue()
        assertThat(budget.limitBytes).isEqualTo(307_200L)
        assertThat(budget.attachmentTargetBytes).isEqualTo(307_200L - MmsSizeBudget.PDU_ENVELOPE_MARGIN_BYTES)
        assertThat(budget.attachmentTargetBytes).isEqualTo(299_008L)
    }

    @Test
    fun `the margin covers the measured envelope many times over yet costs under three percent of the default limit`() {
        // The reporter's single-image PDU: 326 215-byte attachment -> 326 369-byte PDU = 154 bytes of envelope.
        val measuredEnvelope = 326_369L - 326_215L
        assertThat(MmsSizeBudget.PDU_ENVELOPE_MARGIN_BYTES).isAtLeast(measuredEnvelope * 20)
        assertThat(MmsSizeBudget.PDU_ENVELOPE_MARGIN_BYTES).isEqualTo(8_192L)
        assertThat(MmsSizeBudget.PDU_ENVELOPE_MARGIN_BYTES * 100 / MmsSizeBudget.AOSP_DEFAULT_MAX_MESSAGE_SIZE).isLessThan(3)
    }

    @Test
    fun `an unknown limit assumes the AOSP default - the value the platform applies when carrier config is silent`() {
        listOf(null, 0, -1).forEach { carrier ->
            val budget = MmsSizeBudget.forCarrier(carrier)
            assertThat(budget.limitKnown).isFalse()
            assertThat(budget.carrierMaxBytes).isNull()
            assertThat(budget.limitBytes).isEqualTo(MmsSizeBudget.AOSP_DEFAULT_MAX_MESSAGE_SIZE.toLong())
            assertThat(budget.limitBytes).isEqualTo(307_200L)
            assertThat(budget.attachmentTargetBytes).isEqualTo(299_008L)
        }
    }

    @Test
    fun `a generous carrier keeps its whole allowance - no compression down to a fixed budget`() {
        val budget = MmsSizeBudget.forCarrier(1_048_576)

        assertThat(budget.limitBytes).isEqualTo(1_048_576L)
        assertThat(budget.attachmentTargetBytes).isEqualTo(1_048_576L - 8_192L)
        assertThat(budget.attachmentTargetBytes).isGreaterThan(MmsSizeBudget.forCarrier(307_200).attachmentTargetBytes)
    }

    @Test
    fun `fits is the platform's own pre-network check - the reporter's PDU fails it, a fitted one passes`() {
        val default = MmsSizeBudget.forCarrier(307_200)
        assertThat(default.fits(326_369L)).isFalse()
        assertThat(default.fits(307_200L)).isTrue()
        assertThat(default.fits(307_201L)).isFalse()
        assertThat(default.fits(0L)).isFalse()
        // The same PDU on a 1 MiB carrier is fine.
        assertThat(MmsSizeBudget.forCarrier(1_048_576).fits(326_369L)).isTrue()
    }

    @Test
    fun `a nonsensically small carrier value never drives the target to zero`() {
        assertThat(MmsSizeBudget.forCarrier(100).attachmentTargetBytes).isEqualTo(1_024L)
    }
}
