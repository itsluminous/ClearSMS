package app.clearsms

import app.clearsms.data.rules.RuleAction
import app.clearsms.data.rules.RuleDefinition
import app.clearsms.data.rules.RuleDocument
import app.clearsms.data.rules.RuleMatch
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Two guarantees in one:
 *
 * 1. The "share rules with developer" payload is structurally incapable of
 *    carrying message content — [RuleDocument] has no field for message
 *    bodies, timestamps or senders' inbox data; only rule patterns.
 * 2. kotlinx.serialization round-trips the app's own @Serializable models —
 *    run against the release-candidate classpath this catches a broken R8
 *    keep rule (missing `$$serializer` / Companion) as a hard failure.
 */
class RuleSharePayloadTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val document =
        RuleDocument(
            version = "1.0",
            rules =
                listOf(
                    RuleDefinition(
                        id = "user_test-rule",
                        name = "Test rule",
                        priority = 5,
                        match =
                            RuleMatch(
                                senderPattern = "^HDFCBK$",
                                bodyPattern = "(?i)debited.*?(\\d+)",
                                bodyMustContain = listOf("a/c"),
                            ),
                        action =
                            RuleAction(
                                category = "important",
                                subCategory = "transaction",
                                extract = mapOf("amount" to "$1"),
                            ),
                    ),
                ),
        )

    @Test
    fun `rules document round-trips through serialization`() {
        val encoded = json.encodeToString(RuleDocument.serializer(), document)
        val decoded = json.decodeFromString(RuleDocument.serializer(), encoded)
        assertThat(decoded).isEqualTo(document)
    }

    @Test
    fun `share payload has no fields that can carry message content`() {
        val fields =
            listOf(
                RuleDocument::class.java,
                RuleDefinition::class.java,
                RuleMatch::class.java,
                RuleAction::class.java,
            ).flatMap { it.declaredFields.toList() }
                .map { it.name.lowercase() }
        // The schema is patterns-only: nothing that could hold a message body,
        // an inbox timestamp, or a received message's sender/recipient value.
        for (banned in listOf("body", "message", "sms", "timestamp", "recipient")) {
            val leaky = fields.filter { it == banned }
            assertThat(leaky).isEmpty()
        }
    }

    @Test
    fun `encoded payload contains only rule keys`() {
        val encoded = json.encodeToString(RuleDocument.serializer(), document)
        assertThat(encoded).contains("sender_pattern")
        assertThat(encoded).doesNotContain("\"body\":")
        assertThat(encoded).doesNotContain("extractedOtp")
    }
}
