package app.clearsms.ui.conversation

import app.clearsms.ui.conversation.MessageSelectionAction.ADD_RULE
import app.clearsms.ui.conversation.MessageSelectionAction.COPY
import app.clearsms.ui.conversation.MessageSelectionAction.COPY_OTP
import app.clearsms.ui.conversation.MessageSelectionAction.DELETE
import app.clearsms.ui.conversation.MessageSelectionAction.FORWARD
import app.clearsms.ui.conversation.MessageSelectionAction.MORE_DETAILS
import app.clearsms.ui.conversation.MessageSelectionAction.SELECT_ALL
import app.clearsms.ui.conversation.MessageSelectionAction.SHARE
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The conversation selection bar's layout as pure logic (issue #61): the
 * default swap (More details inline, Forward in the overflow), the
 * three-inline-plus-overflow cap that keeps the count unwrapped, user
 * orders resolved against every kind of selection, and the invariant that
 * no applicable action is ever lost between the two lists.
 */
class ConversationSelectionBarLayoutTest {
    private val layout = ConversationSelectionBarLayout
    private val all = MessageSelectionAction.entries.toList()

    @Test
    fun `default - copy, delete and more details inline for a single message, forward in the overflow`() {
        val single = layout.resolveDefault(singleMessage = true, hasOtp = false)
        assertThat(single.inline).containsExactly(COPY, DELETE, MORE_DETAILS).inOrder()
        assertThat(single.overflow).containsExactly(FORWARD, SHARE, SELECT_ALL, ADD_RULE).inOrder()
        // Forward is still reachable - one tap further, where Share lives.
        assertThat(single.overflow.first()).isEqualTo(FORWARD)
    }

    @Test
    fun `default - more details is single-selection only and forward fills its slot, no hole`() {
        val multi = layout.resolveDefault(singleMessage = false, hasOtp = false)
        assertThat(multi.inline).hasSize(ConversationSelectionBarLayout.INLINE_SLOTS)
        assertThat(multi.inline).containsExactly(COPY, DELETE, FORWARD).inOrder()
        assertThat(multi.inline).doesNotContain(MORE_DETAILS)
        assertThat(multi.overflow).containsExactly(SHARE, SELECT_ALL).inOrder()
        assertThat(multi.overflow).doesNotContain(MORE_DETAILS)
    }

    @Test
    fun `default - copy OTP appears only for a single OTP message, after share and select-all`() {
        val otp = layout.resolveDefault(singleMessage = true, hasOtp = true)
        assertThat(otp.overflow).containsExactly(FORWARD, SHARE, SELECT_ALL, COPY_OTP, ADD_RULE).inOrder()
        assertThat(layout.resolveDefault(singleMessage = true, hasOtp = false).overflow).doesNotContain(COPY_OTP)
        // An OTP flag on a multi-selection means nothing: still no Copy OTP.
        assertThat(layout.resolveDefault(singleMessage = false, hasOtp = true).overflow).doesNotContain(COPY_OTP)
    }

    @Test
    fun `the default order is the enum's declaration order`() {
        assertThat(layout.defaultOrder).isEqualTo(all)
        assertThat(layout.defaultOrder.take(3)).containsExactly(COPY, DELETE, MORE_DETAILS).inOrder()
        assertThat(layout.defaultOrder.indexOf(FORWARD)).isGreaterThan(layout.defaultOrder.indexOf(MORE_DETAILS))
    }

    @Test
    fun `a custom order moves its first three applicable actions inline`() {
        val order = listOf(FORWARD, SHARE, SELECT_ALL, COPY, DELETE, MORE_DETAILS, COPY_OTP, ADD_RULE)
        val bar = layout.resolve(order, singleMessage = true, hasOtp = true)
        assertThat(bar.inline).containsExactly(FORWARD, SHARE, SELECT_ALL).inOrder()
        assertThat(bar.overflow).containsExactly(COPY, DELETE, MORE_DETAILS, COPY_OTP, ADD_RULE).inOrder()
    }

    @Test
    fun `a gated action placed first is skipped when it does not apply and the next one moves up`() {
        val order = listOf(COPY_OTP, MORE_DETAILS, ADD_RULE, COPY, DELETE, FORWARD, SHARE, SELECT_ALL)
        // Single OTP message: all three gated actions apply and fill the bar.
        val otp = layout.resolve(order, singleMessage = true, hasOtp = true)
        assertThat(otp.inline).containsExactly(COPY_OTP, MORE_DETAILS, ADD_RULE).inOrder()
        assertThat(otp.overflow).containsExactly(COPY, DELETE, FORWARD, SHARE, SELECT_ALL).inOrder()
        // Single non-OTP message: Copy OTP is gone, Copy steps into the third slot.
        val plain = layout.resolve(order, singleMessage = true, hasOtp = false)
        assertThat(plain.inline).containsExactly(MORE_DETAILS, ADD_RULE, COPY).inOrder()
        assertThat(plain.inline).hasSize(ConversationSelectionBarLayout.INLINE_SLOTS)
        // Multi-select: none of the gated trio applies; the bar is still full.
        val multi = layout.resolve(order, singleMessage = false, hasOtp = true)
        assertThat(multi.inline).containsExactly(COPY, DELETE, FORWARD).inOrder()
        assertThat(multi.overflow).containsExactly(SHARE, SELECT_ALL).inOrder()
        assertThat(multi.inline + multi.overflow).containsNoneOf(COPY_OTP, MORE_DETAILS, ADD_RULE)
    }

    @Test
    fun `the inline count is a layout cap - never exceeded by any order or selection`() {
        assertThat(ConversationSelectionBarLayout.INLINE_SLOTS).isEqualTo(3)
        for (order in listOf(all, all.reversed(), all.shuffled(kotlin.random.Random(61)))) {
            for (single in listOf(true, false)) {
                for (otp in listOf(true, false)) {
                    val bar = layout.resolve(order, single, otp)
                    assertThat(bar.inline.size).isAtMost(ConversationSelectionBarLayout.INLINE_SLOTS)
                    // Five actions apply to every selection and three fit, so the
                    // bar is always full and the overflow is never empty.
                    assertThat(bar.inline).hasSize(ConversationSelectionBarLayout.INLINE_SLOTS)
                    assertThat(bar.overflow).isNotEmpty()
                }
            }
        }
        val alwaysApplicable = all.filter { it.appliesTo(singleMessage = false, hasOtp = false) }
        assertThat(alwaysApplicable.size).isGreaterThan(ConversationSelectionBarLayout.INLINE_SLOTS)
    }

    @Test
    fun `nothing is lost - every applicable action is in exactly one of the two lists`() {
        for (order in listOf(all, all.reversed(), listOf(ADD_RULE, SELECT_ALL))) {
            for (single in listOf(true, false)) {
                for (otp in listOf(true, false)) {
                    val bar = layout.resolve(order, single, otp)
                    val applicable = all.filter { it.appliesTo(single, otp) }
                    assertThat(bar.inline + bar.overflow).containsExactlyElementsIn(applicable)
                    assertThat(bar.inline).containsNoneIn(bar.overflow)
                }
            }
        }
    }

    @Test
    fun `a partial or duplicated order resolves through the shared mechanism - missing actions appended`() {
        // A stored order from a build that had fewer actions, with a repeat.
        val partial = listOf(FORWARD, COPY, FORWARD)
        assertThat(layout.ordered(partial))
            .containsExactly(FORWARD, COPY, DELETE, MORE_DETAILS, SHARE, SELECT_ALL, COPY_OTP, ADD_RULE)
            .inOrder()
        assertThat(layout.ordered(emptyList())).isEqualTo(all)
        // A newly added action is simply the last row: no migration needed.
        val withoutDetails = all - MORE_DETAILS
        assertThat(layout.ordered(withoutDetails).last()).isEqualTo(MORE_DETAILS)
        assertThat(layout.resolve(withoutDetails, singleMessage = true, hasOtp = false).overflow).contains(MORE_DETAILS)
    }

    @Test
    fun `share is never dropped - it applies to every selection`() {
        for (single in listOf(true, false)) {
            for (otp in listOf(true, false)) {
                val bar = layout.resolveDefault(single, otp)
                assertThat(bar.inline + bar.overflow).contains(SHARE)
            }
        }
    }
}
