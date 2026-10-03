package app.clearsms.ui.conversation

import app.clearsms.R
import app.clearsms.mms.SendFailureReason
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File
import app.clearsms.testing.DefaultStrings

/**
 * The user-facing side of a failed send: every reason (and no reason) has
 * an explanation and a bubble label, the wording says only what the phone
 * reported, and only genuinely transient trouble is told a retry may work.
 */
class SendFailureTextTest {
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
            R.string.send_failure_no_mms_network to "send_failure_no_mms_network",
            R.string.send_failure_apn to "send_failure_apn",
            R.string.send_failure_http to "send_failure_http",
            R.string.send_failure_transient to "send_failure_transient",
            R.string.send_failure_pdu_rejected to "send_failure_pdu_rejected",
            R.string.send_failure_exceeds_carrier_limit to "send_failure_exceeds_carrier_limit",
            R.string.send_failure_carrier_disabled to "send_failure_carrier_disabled",
            R.string.send_failure_sim_unavailable to "send_failure_sim_unavailable",
            R.string.send_failure_no_service to "send_failure_no_service",
            R.string.send_failure_short_code_blocked to "send_failure_short_code_blocked",
            R.string.send_failure_dispatch to "send_failure_dispatch",
            R.string.send_failure_unknown to "send_failure_unknown",
            R.string.send_failure_data_sim_hint to "send_failure_data_sim_hint",
            R.string.conversation_not_sent to "conversation_not_sent",
            R.string.conversation_not_sent_no_mms_network to "conversation_not_sent_no_mms_network",
            R.string.conversation_not_sent_apn to "conversation_not_sent_apn",
            R.string.conversation_not_sent_http to "conversation_not_sent_http",
            R.string.conversation_not_sent_pdu_rejected to "conversation_not_sent_pdu_rejected",
            R.string.conversation_not_sent_exceeds_carrier_limit to "conversation_not_sent_exceeds_carrier_limit",
            R.string.conversation_not_sent_transient to "conversation_not_sent_transient",
            R.string.conversation_not_sent_carrier_disabled to "conversation_not_sent_carrier_disabled",
            R.string.conversation_not_sent_sim_unavailable to "conversation_not_sent_sim_unavailable",
            R.string.conversation_not_sent_no_service to "conversation_not_sent_no_service",
            R.string.conversation_not_sent_short_code_blocked to "conversation_not_sent_short_code_blocked",
            R.string.conversation_not_sent_dispatch to "conversation_not_sent_dispatch",
        )

    private fun explanation(reason: SendFailureReason?): String = string(resourceName.getValue(SendFailureText.explanationRes(reason)))

    private fun bubble(reason: SendFailureReason?): String = string(resourceName.getValue(SendFailureText.bubbleLabelRes(reason)))

    @Test
    fun `every reason and the absent reason have a distinct explanation`() {
        val reasons = SendFailureReason.entries + null
        val explanations = reasons.map { SendFailureText.explanationRes(it) }
        // UNKNOWN and null deliberately share one text; all others differ.
        assertThat(explanations.toSet()).hasSize(SendFailureReason.entries.size)
        assertThat(SendFailureText.explanationRes(null)).isEqualTo(SendFailureText.explanationRes(SendFailureReason.UNKNOWN))
    }

    @Test
    fun `the bubble label always starts with Not sent and adds the reason in a few words`() {
        (SendFailureReason.entries + null).forEach { reason ->
            val label = bubble(reason)
            assertWithMessage("$reason").that(label).startsWith("Not sent")
            // A status line, not a paragraph.
            assertWithMessage("$reason").that(label.length).isAtMost(40)
        }
        assertThat(bubble(null)).isEqualTo("Not sent")
        assertThat(bubble(SendFailureReason.UNKNOWN)).isEqualTo("Not sent")
        assertThat(bubble(SendFailureReason.NO_MMS_NETWORK)).isNotEqualTo("Not sent")
    }

    @Test
    fun `structural reasons never promise that a retry will work`() {
        val retryWords = listOf("retry", "try again", "retrying")
        SendFailureReason.entries.forEach { reason ->
            val text = explanation(reason).lowercase()
            if (SendFailureText.suggestsRetry(reason)) {
                assertWithMessage("$reason").that(retryWords.any { it in text }).isTrue()
            } else {
                assertWithMessage("$reason must not invite a retry: $text").that(retryWords.none { it in text }).isTrue()
            }
        }
        assertThat(SendFailureText.suggestsRetry(SendFailureReason.TRANSIENT)).isTrue()
        // Issue #51: four manual retries in four seconds, each answered in
        // ~25 ms - the platform rejects the PDU identically every time, so
        // the dialog must not say "retrying may work".
        assertThat(SendFailureText.suggestsRetry(SendFailureReason.PDU_REJECTED)).isFalse()
        assertThat(SendFailureText.suggestsRetry(SendFailureReason.NO_MMS_NETWORK)).isFalse()
        assertThat(SendFailureText.suggestsRetry(SendFailureReason.CARRIER_DISABLED)).isFalse()
        assertThat(SendFailureText.suggestsRetry(null)).isFalse()
    }

    @Test
    fun `the wording states what was reported and does not diagnose the carrier`() {
        // NO_MMS_NETWORK: the connection "did not come up" - the app cannot
        // know why - and the SIM-wide truth is stated without naming a country.
        val noNetwork = explanation(SendFailureReason.NO_MMS_NETWORK)
        assertThat(noNetwork).contains("MMS connection did not come up")
        assertThat(noNetwork).contains("no app can send one")
        assertThat(noNetwork).doesNotContain("Indian")
        // The app's own failure names the hand-over, not the carrier, and is
        // transport-neutral because the SMS path records it too.
        val dispatch = explanation(SendFailureReason.DISPATCH_FAILED)
        assertThat(dispatch).contains("could not be handed to")
        assertThat(dispatch).doesNotContain("carrier")
        assertThat(dispatch).doesNotContain("MMS")
        // Unknown says exactly that.
        assertThat(explanation(SendFailureReason.UNKNOWN)).contains("without saying why")
        // The SMS-side reasons (GitHub #75 made short-code replies possible):
        // no service is "had no mobile network", not a diagnosis of where
        // the user was; a short code the PHONE blocked says so and makes
        // clear the carrier never saw it - the service is not blamed.
        assertThat(explanation(SendFailureReason.NO_SERVICE)).contains("had no mobile network")
        val blocked = explanation(SendFailureReason.SHORT_CODE_BLOCKED)
        assertThat(blocked).contains("The phone did not send this")
        assertThat(blocked).contains("possibly chargeable")
        assertThat(blocked).contains("carrier was never contacted")
        assertThat(bubble(SendFailureReason.SHORT_CODE_BLOCKED)).isEqualTo("Not sent · blocked by phone")
        assertThat(bubble(SendFailureReason.NO_SERVICE)).isEqualTo("Not sent · no mobile network")
        // Every explanation is short: at most two sentences and under 200 chars.
        SendFailureReason.entries.forEach { reason ->
            val text = explanation(reason)
            assertWithMessage("$reason").that(text.length).isAtMost(200)
            assertWithMessage("$reason").that(text.count { it == '.' }).isAtMost(3)
        }
    }

    @Test
    fun `the bubble, the Retry dialog and the details row all read from this one mapping`() {
        fun source(path: String) = File("src/main/kotlin/app/clearsms", path).readText()
        val screen = source("ui/conversation/ConversationScreen.kt")
        assertThat(screen).contains("SendFailureText.bubbleLabelRes(")
        assertThat(screen).contains("SendFailureText.explanationRes(")
        assertThat(source("ui/conversation/MessageDetailsDialog.kt")).contains("SendFailureText.explanationRes(row.reason)")
        // No second, drifting when-table over the reasons anywhere in the UI.
        File("src/main/kotlin/app/clearsms/ui")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "SendFailureText.kt" }
            .forEach { file ->
                assertWithMessage(file.path).that(file.readText()).doesNotContain("R.string.send_failure_")
            }
    }

    @Test
    fun `the data-SIM addendum is guidance with slot numbers - not a verdict, not a carrier`() {
        val hint = string(resourceName.getValue(SendFailureText.dataSimHintRes()))
        // Placeholders: %1$d = the sending slot, %2$d = the data SIM's slot.
        assertThat(hint).contains("sent from SIM %1\$d")
        assertThat(hint).contains("mobile data is on SIM %2\$d")
        // Hedged: "may only work", never "does not work" / "cannot".
        assertThat(hint).contains("may only work")
        assertThat(hint.lowercase()).doesNotContain("cannot")
        assertThat(hint.lowercase()).doesNotContain("will not")
        // Actionable both ways: change the sending SIM, or change the data SIM.
        assertThat(hint).contains("try sending from SIM %2\$d")
        assertThat(hint).contains("make SIM %1\$d the mobile-data SIM")
        // Slots only - no carrier, number or id placeholder.
        assertThat(hint).doesNotContain("%s")
        assertThat(hint).doesNotContain("%1\$s")
        assertThat(hint).doesNotContain("%2\$s")
        // It is an addendum, distinct from every standalone explanation.
        assertThat(SendFailureReason.entries.map { SendFailureText.explanationRes(it) }).doesNotContain(SendFailureText.dataSimHintRes())
    }
}
