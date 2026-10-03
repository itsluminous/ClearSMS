package app.clearsms.ui.common

import app.clearsms.R
import app.clearsms.sms.SenderRepliability.Repliability
import app.clearsms.ui.conversation.ConversationUiState
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File
import app.clearsms.testing.DefaultStrings

/**
 * The wording of the conversation's reply notice, per verdict (GitHub #75).
 * The resource used for each case is pinned so a future edit cannot quietly
 * put "This sender doesn't accept replies" back in front of a short code
 * that accepts them; the text itself is checked for what it may and may
 * not claim. Source-level where the repo has no Compose harness.
 */
class RepliabilityTextTest {
    private val strings = DefaultStrings.ui

    private fun string(name: String): String =
        Regex("""<string name="$name">(.*?)</string>""")
            .find(strings)
            ?.groupValues
            ?.get(1)
            ?.replace("\\'", "'")
            ?: error("missing string $name")

    private val resourceName =
        mapOf(
            R.string.conversation_short_code_may_not_reply to "conversation_short_code_may_not_reply",
            R.string.conversation_reply_needs_number to "conversation_reply_needs_number",
            R.string.conversation_reply_no_address to "conversation_reply_no_address",
            R.string.conversation_reply_anyway to "conversation_reply_anyway",
        )

    private fun message(repliability: Repliability) = string(resourceName.getValue(RepliabilityText.messageRes(repliability)))

    @Test
    fun `each notice case uses its own resource and a number gets none`() {
        assertThat(RepliabilityText.messageRes(Repliability.SHORT_CODE)).isEqualTo(R.string.conversation_short_code_may_not_reply)
        assertThat(RepliabilityText.messageRes(Repliability.UNADDRESSABLE_NAME)).isEqualTo(R.string.conversation_reply_needs_number)
        assertThat(RepliabilityText.messageRes(Repliability.INVALID)).isEqualTo(R.string.conversation_reply_no_address)
        assertThat(
            runCatching { RepliabilityText.messageRes(Repliability.NUMBER) }.exceptionOrNull(),
        ).isInstanceOf(IllegalStateException::class.java)
        // Three distinct texts.
        assertThat(
            listOf(Repliability.SHORT_CODE, Repliability.UNADDRESSABLE_NAME, Repliability.INVALID)
                .map { RepliabilityText.messageRes(it) }
                .toSet(),
        ).hasSize(3)
    }

    @Test
    fun `a short code is a guess - hedged, and with a way to reply anyway`() {
        val text = message(Repliability.SHORT_CODE)
        assertThat(text).contains("may not accept replies")
        assertThat(text.lowercase()).doesNotContain("doesn't")
        assertThat(text.lowercase()).doesNotContain("does not")
        assertThat(text.lowercase()).doesNotContain("cannot")
        assertThat(RepliabilityText.offersReplyAnyway(Repliability.SHORT_CODE)).isTrue()
        assertThat(string(resourceName.getValue(RepliabilityText.replyAnywayRes()))).isEqualTo("Reply anyway")
    }

    @Test
    fun `an alphanumeric id states the phone's limit, not a refusal by the sender`() {
        val text = message(Repliability.UNADDRESSABLE_NAME)
        assertThat(text).contains("only be sent to a number")
        assertThat(text.lowercase()).doesNotContain("accept")
        assertThat(text.lowercase()).doesNotContain("refuse")
        // No "reply anyway": the platform cannot address it, and a mixed id
        // would be mangled to a wrong number rather than failing.
        assertThat(RepliabilityText.offersReplyAnyway(Repliability.UNADDRESSABLE_NAME)).isFalse()
        assertThat(RepliabilityText.offersReplyAnyway(Repliability.INVALID)).isFalse()
        assertThat(RepliabilityText.offersReplyAnyway(Repliability.NUMBER)).isFalse()
    }

    @Test
    fun `no notice asserts that a sender does not accept replies`() {
        listOf(Repliability.SHORT_CODE, Repliability.UNADDRESSABLE_NAME, Repliability.INVALID).forEach {
            val text = message(it).lowercase()
            assertWithMessage("$it: $text").that(text).doesNotContain("doesn't accept")
            assertWithMessage("$it: $text").that(text).doesNotContain("does not accept")
            assertWithMessage("$it").that(message(it).length).isAtMost(90)
        }
        // The old assertion is gone from the resources entirely.
        assertThat(strings).doesNotContain("conversation_not_repliable")
        assertThat(strings.lowercase()).doesNotContain("doesn\\'t accept replies")
    }

    @Test
    fun `the composer shows for a number outright and for a short code only after Reply anyway`() {
        assertThat(ConversationUiState(repliability = Repliability.NUMBER).repliable).isTrue()
        assertThat(ConversationUiState(repliability = Repliability.SHORT_CODE).repliable).isFalse()
        assertThat(ConversationUiState(repliability = Repliability.SHORT_CODE, replyAnyway = true).repliable).isTrue()
        // Reply-anyway never opens the composer for something unaddressable.
        assertThat(ConversationUiState(repliability = Repliability.UNADDRESSABLE_NAME, replyAnyway = true).repliable).isFalse()
        assertThat(ConversationUiState(repliability = Repliability.INVALID, replyAnyway = true).repliable).isFalse()
        // The default (nothing loaded yet) shows no composer.
        assertThat(ConversationUiState().repliable).isFalse()
    }

    @Test
    fun `a short code the user saved as a contact gets the composer directly, an unsaved one the notice`() {
        // GitHub #75's reporter had saved 80122 as "O2 Zusatzvolumen": that is
        // the user telling us they correspond with it.
        assertThat(ConversationUiState(repliability = Repliability.SHORT_CODE, isContact = true).repliable).isTrue()
        assertThat(ConversationUiState(repliability = Repliability.SHORT_CODE, isContact = false).repliable).isFalse()
        // A bundled-directory name is NOT the user's intent - still the notice.
        assertThat(ConversationUiState(repliability = Repliability.SHORT_CODE, isKnownSender = true).repliable).isFalse()
        // The affordance never widens what is addressable: a saved
        // alphanumeric id ("O2" in the address book) stays closed.
        assertThat(ConversationUiState(repliability = Repliability.UNADDRESSABLE_NAME, isContact = true).repliable).isFalse()
        assertThat(ConversationUiState(repliability = Repliability.INVALID, isContact = true).repliable).isFalse()
        // And a saved number is unchanged - the composer either way.
        assertThat(ConversationUiState(repliability = Repliability.NUMBER, isContact = true).repliable).isTrue()
    }

    @Test
    fun `the screen reads the notice through this one mapping and the ViewModel classifies through the shared predicate`() {
        fun source(path: String) = File("src/main/kotlin/app/clearsms", path).readText()
        val screen = source("ui/conversation/ConversationScreen.kt")
        val bar = source("ui/components/MessageComposerBar.kt")
        val viewModel = source("ui/conversation/ConversationViewModel.kt")
        assertThat(screen).contains("state.repliable ->")
        assertThat(screen).contains("ReplyNoticeBar(")
        assertThat(screen).contains("onReplyAnyway = viewModel::replyAnyway,")
        assertThat(bar).contains("RepliabilityText.messageRes(repliability)")
        assertThat(bar).contains("RepliabilityText.offersReplyAnyway(repliability)")
        assertThat(bar).contains("RepliabilityText.replyAnywayRes()")
        assertThat(viewModel).contains("SenderRepliability.classifyOnDevice(it)")
        // The saved-contact signal is the address-book one, not the directory one.
        assertThat(viewModel).contains("isContact = display?.isContact ?: false,")
        // No second, drifting notice string anywhere in the UI.
        File("src/main/kotlin/app/clearsms/ui")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "RepliabilityText.kt" }
            .forEach { file ->
                val text = file.readText()
                resourceName.values.forEach { name ->
                    assertWithMessage("${file.path} uses $name directly").that(text).doesNotContain("R.string.$name")
                }
                assertWithMessage(file.path).that(text).doesNotContain("NotRepliableBar")
            }
    }
}
