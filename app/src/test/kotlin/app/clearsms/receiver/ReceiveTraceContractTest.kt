package app.clearsms.receiver

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Issue #152 ("some messages are never received"): a message the platform
 * delivered to the app but that never reached storage used to leave NO
 * trace, because the receivers' first diagnostic line came after the
 * provider and Room writes. This pins the trace that makes such a loss
 * visible in a shared report:
 *
 * - each receiver logs "deliver received" / "wap push received" as the FIRST
 *   thing after the action check - before PDU decoding, before `goAsync()`,
 *   before any write;
 * - the SMS outcome lines ("ingested", "incoming message failed") carry the
 *   same `delivery` number as their opening line, so a report shows an
 *   opening with no outcome;
 * - "ingested" carries the elapsed milliseconds, the time spent inside the
 *   platform's broadcast window.
 *
 * Source-contract test: the receivers need a platform broadcast to run, so
 * their ORDER is asserted on the source text.
 */
class ReceiveTraceContractTest {
    private fun source(path: String) = File("src/main/kotlin/app/clearsms", path).readText()

    private fun String.indexOfOrFail(needle: String): Int {
        val at = indexOf(needle)
        assertWithMessage("expected to find: $needle").that(at).isAtLeast(0)
        return at
    }

    @Test
    fun `sms receiver traces the delivery before decoding, goAsync and every write`() {
        val src = source("receiver/SmsReceiver.kt")
        val trace = src.indexOfOrFail("\"deliver received\"")
        assertThat(trace).isLessThan(src.indexOfOrFail("extractParts(intent, receivedAtMs = receivedAtMs)"))
        assertThat(trace).isLessThan(src.indexOfOrFail("goAsync()"))
        assertThat(trace).isLessThan(src.indexOfOrFail("telephonyWriter.writeInbox("))
        assertThat(trace).isLessThan(src.indexOfOrFail("messageRepository.ingestIncoming("))
        // The action check is the only thing allowed before it.
        assertThat(src.indexOfOrFail("if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return")).isLessThan(trace)
        // Content-free by construction: a PDU count and a SIM flag, no PDU.
        val call = src.substring(trace, src.indexOf(')', src.indexOf("flag(\"sim\"", trace)) + 1)
        assertThat(call).contains("count(\"delivery\", delivery)")
        assertThat(call).contains("count(\"pdus\", pduCount(intent))")
        assertThat(call).doesNotContain("body")
    }

    @Test
    fun `sms outcome lines carry the delivery number and the elapsed time`() {
        val src = source("receiver/SmsReceiver.kt")
        val ingested = src.indexOfOrFail("\n            \"ingested\",")
        val ingestedCall = src.substring(ingested, src.indexOf("flag(\"sim\", subscriptionId != null)", ingested))
        assertThat(ingestedCall).contains("count(\"delivery\", delivery)")
        assertThat(ingestedCall).contains("count(\"elapsedMs\", System.currentTimeMillis() - receivedAtMs)")
        val failed = src.indexOfOrFail("\"incoming message failed\",\n")
        val failedCall = src.substring(failed, src.indexOf("count(\"parts\", parts.size)", failed))
        assertThat(failedCall).contains("count(\"delivery\", delivery)")
    }

    @Test
    fun `wap push receiver traces the push before the null check, goAsync and the store`() {
        val src = source("receiver/MmsWapPushReceiver.kt")
        val trace = src.indexOfOrFail("\"wap push received\"")
        assertThat(trace).isLessThan(src.indexOfOrFail("if (pdu == null)"))
        assertThat(trace).isLessThan(src.indexOfOrFail("goAsync()"))
        assertThat(trace).isLessThan(src.indexOfOrFail("mmsInbound.onNotification("))
        assertThat(src.indexOfOrFail("if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return")).isLessThan(trace)
        val call = src.substring(trace, src.indexOf(')', src.indexOf("flag(\"hasSubscription\"", trace)) + 1)
        assertThat(call).contains("count(\"pduBytes\", pdu?.size ?: -1)")
    }
}
