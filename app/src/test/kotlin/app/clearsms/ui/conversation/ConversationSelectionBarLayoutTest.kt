package app.clearsms.ui.conversation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the conversation selection bar's 3-inline-plus-overflow contract
 * (the count must never wrap - same standard as the inbox bar): exactly
 * three inline actions with Forward among them, Share always reachable in
 * the overflow, and the single-message-only entries appearing only then.
 */
class ConversationSelectionBarLayoutTest {
    @Test
    fun `exactly three inline actions with forward inline`() {
        assertThat(ConversationSelectionBarLayout.inlineActions).hasSize(3)
        assertThat(ConversationSelectionBarLayout.inlineActions)
            .contains(MessageSelectionAction.FORWARD)
    }

    @Test
    fun `share is always in the overflow, never inline`() {
        assertThat(ConversationSelectionBarLayout.inlineActions)
            .doesNotContain(MessageSelectionAction.SHARE)
        assertThat(ConversationSelectionBarLayout.overflowActions(singleMessage = false, hasOtp = false))
            .contains(MessageSelectionAction.SHARE)
        assertThat(ConversationSelectionBarLayout.overflowActions(singleMessage = true, hasOtp = true))
            .contains(MessageSelectionAction.SHARE)
    }

    @Test
    fun `single-message entries appear only for a single selection`() {
        val multi = ConversationSelectionBarLayout.overflowActions(singleMessage = false, hasOtp = true)
        assertThat(multi).doesNotContain(MessageSelectionAction.COPY_OTP)
        assertThat(multi).doesNotContain(MessageSelectionAction.ADD_RULE)
        val singleOtp = ConversationSelectionBarLayout.overflowActions(singleMessage = true, hasOtp = true)
        assertThat(singleOtp).contains(MessageSelectionAction.COPY_OTP)
        assertThat(singleOtp).contains(MessageSelectionAction.ADD_RULE)
        val singlePlain = ConversationSelectionBarLayout.overflowActions(singleMessage = true, hasOtp = false)
        assertThat(singlePlain).doesNotContain(MessageSelectionAction.COPY_OTP)
    }
}
