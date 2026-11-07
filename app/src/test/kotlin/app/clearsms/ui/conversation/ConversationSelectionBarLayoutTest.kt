package app.clearsms.ui.conversation

import app.clearsms.ui.conversation.MessageSelectionAction.ADD_RULE
import app.clearsms.ui.conversation.MessageSelectionAction.COPY
import app.clearsms.ui.conversation.MessageSelectionAction.COPY_OTP
import app.clearsms.ui.conversation.MessageSelectionAction.DELETE
import app.clearsms.ui.conversation.MessageSelectionAction.FORWARD
import app.clearsms.ui.conversation.MessageSelectionAction.MORE_DETAILS
import app.clearsms.ui.conversation.MessageSelectionAction.SAVE_ATTACHMENT
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
        val single = layout.resolveDefault(singleMessage = true, hasOtp = false, hasAttachment = false)
        assertThat(single.inline).containsExactly(COPY, DELETE, MORE_DETAILS).inOrder()
        assertThat(single.overflow).containsExactly(FORWARD, SHARE, SELECT_ALL, ADD_RULE).inOrder()
        // Forward is still reachable - one tap further, where Share lives.
        assertThat(single.overflow.first()).isEqualTo(FORWARD)
    }

    @Test
    fun `default - more details is single-selection only and forward fills its slot, no hole`() {
        val multi = layout.resolveDefault(singleMessage = false, hasOtp = false, hasAttachment = false)
        assertThat(multi.inline).hasSize(ConversationSelectionBarLayout.INLINE_SLOTS)
        assertThat(multi.inline).containsExactly(COPY, DELETE, FORWARD).inOrder()
        assertThat(multi.inline).doesNotContain(MORE_DETAILS)
        assertThat(multi.overflow).containsExactly(SHARE, SELECT_ALL).inOrder()
        assertThat(multi.overflow).doesNotContain(MORE_DETAILS)
    }

    @Test
    fun `default - copy OTP appears only for a single OTP message, after share and select-all`() {
        val otp = layout.resolveDefault(singleMessage = true, hasOtp = true, hasAttachment = false)
        assertThat(otp.overflow).containsExactly(FORWARD, SHARE, SELECT_ALL, COPY_OTP, ADD_RULE).inOrder()
        assertThat(layout.resolveDefault(singleMessage = true, hasOtp = false, hasAttachment = false).overflow).doesNotContain(COPY_OTP)
        // An OTP flag on a multi-selection means nothing: still no Copy OTP.
        assertThat(layout.resolveDefault(singleMessage = false, hasOtp = true, hasAttachment = false).overflow).doesNotContain(COPY_OTP)
    }

    @Test
    fun `the default order is the enum's declaration order`() {
        assertThat(layout.defaultOrder).isEqualTo(all)
        assertThat(layout.defaultOrder.take(3)).containsExactly(COPY, DELETE, MORE_DETAILS).inOrder()
        assertThat(layout.defaultOrder.indexOf(FORWARD)).isGreaterThan(layout.defaultOrder.indexOf(MORE_DETAILS))
    }

    @Test
    fun `a custom order moves its first three applicable actions inline`() {
        val order = listOf(FORWARD, SHARE, SELECT_ALL, COPY, DELETE, MORE_DETAILS, COPY_OTP, ADD_RULE, SAVE_ATTACHMENT)
        val bar = layout.resolve(order, singleMessage = true, hasOtp = true, hasAttachment = false)
        assertThat(bar.inline).containsExactly(FORWARD, SHARE, SELECT_ALL).inOrder()
        assertThat(bar.overflow).containsExactly(COPY, DELETE, MORE_DETAILS, COPY_OTP, ADD_RULE).inOrder()
    }

    @Test
    fun `a gated action placed first is skipped when it does not apply and the next one moves up`() {
        val order = listOf(COPY_OTP, MORE_DETAILS, ADD_RULE, COPY, DELETE, FORWARD, SHARE, SELECT_ALL, SAVE_ATTACHMENT)
        // Single OTP message: all three gated actions apply and fill the bar.
        val otp = layout.resolve(order, singleMessage = true, hasOtp = true, hasAttachment = false)
        assertThat(otp.inline).containsExactly(COPY_OTP, MORE_DETAILS, ADD_RULE).inOrder()
        assertThat(otp.overflow).containsExactly(COPY, DELETE, FORWARD, SHARE, SELECT_ALL).inOrder()
        // Single non-OTP message: Copy OTP is gone, Copy steps into the third slot.
        val plain = layout.resolve(order, singleMessage = true, hasOtp = false, hasAttachment = false)
        assertThat(plain.inline).containsExactly(MORE_DETAILS, ADD_RULE, COPY).inOrder()
        assertThat(plain.inline).hasSize(ConversationSelectionBarLayout.INLINE_SLOTS)
        // Multi-select: none of the gated trio applies; the bar is still full.
        val multi = layout.resolve(order, singleMessage = false, hasOtp = true, hasAttachment = false)
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
                    val bar = layout.resolve(order, single, otp, hasAttachment = false)
                    assertThat(bar.inline.size).isAtMost(ConversationSelectionBarLayout.INLINE_SLOTS)
                    // Five actions apply to every selection and three fit, so the
                    // bar is always full and the overflow is never empty.
                    assertThat(bar.inline).hasSize(ConversationSelectionBarLayout.INLINE_SLOTS)
                    assertThat(bar.overflow).isNotEmpty()
                }
            }
        }
        val alwaysApplicable = all.filter { it.appliesTo(singleMessage = false, hasOtp = false, hasAttachment = false) }
        assertThat(alwaysApplicable.size).isGreaterThan(ConversationSelectionBarLayout.INLINE_SLOTS)
    }

    @Test
    fun `nothing is lost - every applicable action is in exactly one of the two lists`() {
        for (order in listOf(all, all.reversed(), listOf(ADD_RULE, SELECT_ALL), listOf(SAVE_ATTACHMENT))) {
            for (single in listOf(true, false)) {
                for (otp in listOf(true, false)) {
                    for (attachment in listOf(true, false)) {
                        val bar = layout.resolve(order, single, otp, attachment)
                        val applicable = all.filter { it.appliesTo(single, otp, attachment) }
                        assertThat(bar.inline + bar.overflow).containsExactlyElementsIn(applicable)
                        assertThat(bar.inline).containsNoneIn(bar.overflow)
                    }
                }
            }
        }
    }

    @Test
    fun `a partial or duplicated order resolves through the shared mechanism - missing actions appended`() {
        // A stored order from a build that had fewer actions, with a repeat.
        val partial = listOf(FORWARD, COPY, FORWARD)
        assertThat(layout.ordered(partial))
            .containsExactly(FORWARD, COPY, DELETE, MORE_DETAILS, SHARE, SELECT_ALL, COPY_OTP, ADD_RULE, SAVE_ATTACHMENT)
            .inOrder()
        assertThat(layout.ordered(emptyList())).isEqualTo(all)
        // A newly added action is simply the last row: no migration needed.
        val withoutDetails = all - MORE_DETAILS
        assertThat(layout.ordered(withoutDetails).last()).isEqualTo(MORE_DETAILS)
        assertThat(
            layout.resolve(withoutDetails, singleMessage = true, hasOtp = false, hasAttachment = false).overflow,
        ).contains(MORE_DETAILS)
    }

    @Test
    fun `save attachment - only for a single message that carries an MMS attachment, last by default`() {
        // Issue #151: a plain SMS has nothing to save, and saving the parts of
        // several messages would mean a blind run of pickers - so the action
        // exists for exactly one attachment-bearing message, in the overflow.
        val saveable = layout.resolveDefault(singleMessage = true, hasOtp = false, hasAttachment = true)
        assertThat(saveable.inline).containsExactly(COPY, DELETE, MORE_DETAILS).inOrder()
        assertThat(saveable.overflow).containsExactly(FORWARD, SHARE, SELECT_ALL, ADD_RULE, SAVE_ATTACHMENT).inOrder()
        assertThat(layout.resolveDefault(singleMessage = true, hasOtp = false, hasAttachment = false).overflow)
            .doesNotContain(SAVE_ATTACHMENT)
        // An attachment flag on a multi-selection means nothing: still no Save.
        val multi = layout.resolveDefault(singleMessage = false, hasOtp = false, hasAttachment = true)
        assertThat(multi.inline + multi.overflow).doesNotContain(SAVE_ATTACHMENT)
        assertThat(SAVE_ATTACHMENT.appliesTo(singleMessage = true, hasOtp = true, hasAttachment = true)).isTrue()
        assertThat(SAVE_ATTACHMENT.appliesTo(singleMessage = false, hasOtp = true, hasAttachment = true)).isFalse()
        // The user may promote it: placed first it takes the first slot when it applies, and steps aside when not.
        val first = listOf(SAVE_ATTACHMENT) + (all - SAVE_ATTACHMENT)
        assertThat(layout.resolve(first, singleMessage = true, hasOtp = false, hasAttachment = true).inline)
            .containsExactly(SAVE_ATTACHMENT, COPY, DELETE)
            .inOrder()
        assertThat(layout.resolve(first, singleMessage = true, hasOtp = false, hasAttachment = false).inline)
            .containsExactly(COPY, DELETE, MORE_DETAILS)
            .inOrder()
    }

    @Test
    fun `an order saved before save attachment existed still resolves - the new action is appended, never lost`() {
        // Exactly what a 0.25.x install stored (the eight actions of that build).
        val stored = listOf(COPY, DELETE, MORE_DETAILS, FORWARD, SHARE, SELECT_ALL, COPY_OTP, ADD_RULE)
        assertThat(layout.ordered(stored)).isEqualTo(stored + SAVE_ATTACHMENT)
        val bar = layout.resolve(stored, singleMessage = true, hasOtp = false, hasAttachment = true)
        assertThat(bar.inline).containsExactly(COPY, DELETE, MORE_DETAILS).inOrder()
        assertThat(bar.overflow.last()).isEqualTo(SAVE_ATTACHMENT)
        // And the user's own arrangement is untouched by the append.
        val custom = listOf(FORWARD, SHARE, COPY, DELETE, MORE_DETAILS, SELECT_ALL, COPY_OTP, ADD_RULE)
        assertThat(layout.ordered(custom).dropLast(1)).isEqualTo(custom)
    }

    @Test
    fun `share is never dropped - it applies to every selection`() {
        for (single in listOf(true, false)) {
            for (otp in listOf(true, false)) {
                val bar = layout.resolveDefault(single, otp, hasAttachment = false)
                assertThat(bar.inline + bar.overflow).contains(SHARE)
            }
        }
    }
}
