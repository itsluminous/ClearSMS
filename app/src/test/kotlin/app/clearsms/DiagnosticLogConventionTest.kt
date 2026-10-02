package app.clearsms

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * The durable half of the diagnostic logger's privacy guarantee. The API
 * shape (`DiagField` has no free-form string factory) stops most leaks at
 * compile time; this test catches the two ways a future edit could still
 * push message content into a shareable report:
 *
 * 1. interpolating or concatenating into the EVENT string
 *    (`Diag.i(TAG, "got $body")`), and
 * 2. handing a sensitive value to a field factory whose runtime check is
 *    weaker than the type (`sender(merged.body)`, `ruleId(otp)`).
 *
 * It scans every `Diag.d/i/w/e(` call in the main source set, reads the
 * FULL argument list (calls span lines), and fails on either pattern.
 * Verified against a deliberately planted `sender(merged.body)` call, which
 * it reported and which was then removed.
 */
class DiagnosticLogConventionTest {
    /** Identifier fragments that name message content or a person. */
    private val forbidden =
        listOf(
            "body",
            "text",
            "address",
            "otp",
            "amount",
            "account",
            "balance",
            "vpa",
            "upi",
            "recipient",
            "destination",
            "phone",
            "number",
            "contact",
            "displayname",
            "merchant",
            "pnr",
            "reference",
            // An attachment's name, path or URI, and an MMS content location
            // (a carrier URL that embeds a per-message token), identify the
            // message as surely as its text does.
            "filename",
            "path",
            "location",
            "transactionid",
        )

    private val callStart = Regex("""\bDiag\.(d|i|w|e)\(""")

    private data class Call(
        val file: File,
        val line: Int,
        val args: String,
    )

    private fun sources(): Sequence<File> =
        File("src/main/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            // The logger's own definition is not a call site.
            .filterNot { it.path.contains("/diagnostics/Diag.kt") }

    /** Every `Diag.x(` call with the text between its balanced parentheses. */
    private fun calls(): List<Call> = sources().flatMap { file -> callsIn(file, file.readText()) }.toList()

    private fun callsIn(
        file: File,
        text: String,
    ): List<Call> =
        callStart
            .findAll(text)
            .map { match ->
                val open = match.range.last
                Call(file, text.substring(0, open).count { it == '\n' } + 1, argumentsAfter(text, open))
            }.toList()

    /** Text from the '(' at [open] to its matching ')', skipping string literals. */
    private fun argumentsAfter(
        text: String,
        open: Int,
    ): String {
        var depth = 0
        var i = open
        var inString = false
        while (i < text.length) {
            val c = text[i]
            when {
                inString -> {
                    if (c == '\\') {
                        i++
                    } else if (c == '"') {
                        inString = false
                    }
                }

                c == '"' -> {
                    inString = true
                }

                c == '(' -> {
                    depth++
                }

                c == ')' -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, i)
                }
            }
            i++
        }
        error("unbalanced parentheses after offset $open")
    }

    /** Top-level comma split, respecting nesting and string literals. */
    private fun topLevelArgs(args: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var inString = false
        val current = StringBuilder()
        var i = 0
        while (i < args.length) {
            val c = args[i]
            when {
                inString -> {
                    if (c == '\\') {
                        current.append(c).append(args.getOrElse(i + 1) { ' ' })
                        i++
                    } else {
                        if (c == '"') inString = false
                        current.append(c)
                    }
                }

                c == '"' -> {
                    inString = true
                    current.append(c)
                }

                c == '(' || c == '{' || c == '[' -> {
                    depth++
                    current.append(c)
                }

                c == ')' || c == '}' || c == ']' -> {
                    depth--
                    current.append(c)
                }

                c == ',' && depth == 0 -> {
                    out += current.toString().trim()
                    current.clear()
                }

                else -> {
                    current.append(c)
                }
            }
            i++
        }
        if (current.isNotBlank()) out += current.toString().trim()
        return out
    }

    private val stringLiteral = Regex("\"(\\\\.|[^\"\\\\])*\"")
    private val nullCheck = Regex("""[A-Za-z_][A-Za-z0-9_.?]*\s*[!=]=\s*null""")
    private val identifier = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

    @Test
    fun `the diagnostic logger has call sites to guard`() {
        // Guards against the convention passing trivially.
        assertThat(calls().size).isAtLeast(20)
    }

    private fun eventViolations(calls: List<Call>): List<String> =
        calls.mapNotNull { call ->
            val event = topLevelArgs(call.args).getOrNull(1) ?: return@mapNotNull "${call.file.path}:${call.line} has no event argument"
            val plain = Regex("""^"[^"$]*"$""").matches(event)
            if (plain) null else "${call.file.path}:${call.line} event is not a plain literal -> $event"
        }

    private fun fieldViolations(calls: List<Call>): List<String> =
        calls.mapNotNull { call ->
            val scrubbed =
                call.args
                    .replace(stringLiteral, "\"\"")
                    // `x.extractedOtp != null` is a presence flag, not the value.
                    .replace(nullCheck, "")
            val leaked =
                identifier
                    .findAll(scrubbed)
                    .map { it.value }
                    .filter { token -> forbidden.any { token.lowercase().contains(it) } }
                    .toList()
            if (leaked.isEmpty()) null else "${call.file.path}:${call.line} passes $leaked -> ${call.args.trim()}"
        }

    @Test
    fun `every Diag event is a plain string literal - no interpolation, no concatenation`() {
        val violations = eventViolations(calls())
        assertWithMessage(
            "Diag events must be constant phrases; every variable travels as a typed DiagField:\n" +
                violations.joinToString("\n"),
        ).that(violations).isEmpty()
    }

    @Test
    fun `no Diag call names a message body, address, OTP, account, VPA, amount or contact`() {
        val violations = fieldViolations(calls())
        assertWithMessage(
            "Diag fields may carry counts, flags, ids, codes, enum names, rule ids and sender ids - " +
                "never message content or anything identifying a person:\n" + violations.joinToString("\n"),
        ).that(violations).isEmpty()
    }

    @Test
    fun `the scanner itself catches a planted body, recipient, file name and interpolated event`() {
        // Self-test: the guard is only worth having if each way the MMS
        // path could leak is actually reported. Multi-line calls, nested
        // factories and a null-check presence flag are all exercised.
        val planted =
            """
            Diag.i(TAG, "mms handover", id("message", messageId), count("bytes", part.data.size))
            Diag.i(TAG, "sent to ${'$'}destination", id("message", messageId))
            Diag.w(
                TAG,
                "attachment staged",
                null,
                mime(part.mimeType),
                count("nameLength", staged.displayName.length),
                count("len", attachment.fileName.length),
            )
            Diag.e(TAG, "failed", e, sender(merged.body), flag("known", notification.contentLocation != null))
            Diag.d(TAG, "download", flag("hasLocation", location != null), count("n", recipients.size))
            """.trimIndent()
        val calls = callsIn(File("planted.kt"), planted)
        assertThat(calls).hasSize(5)
        assertThat(eventViolations(calls)).hasSize(1)
        val leaks = fieldViolations(calls)
        // Line 1 is clean; line 2 leaks only through its event; lines 3-5
        // each name something forbidden (a display/file name, a body, the
        // recipients) - the null-checked contentLocation is a presence
        // flag and is NOT counted.
        assertThat(leaks).hasSize(3)
        val leakedTokens =
            leaks.flatMap { line ->
                Regex("""passes \[([^\]]*)\]""").find(line)!!.groupValues[1].split(", ")
            }
        assertThat(leakedTokens).containsExactly("displayName", "fileName", "body", "recipients")
    }

    @Test
    fun `converted paths no longer use android util Log`() {
        // The paths a user bug report needs (ingest, categorisation, import,
        // backfills, send reports, routing, rules load, backup, the whole
        // MMS pipeline, provider writes, contact/SIM lookups, notification
        // actions, the sender directory) log through Diag so their entries
        // reach the shareable report. Deliberately still on logcat: the
        // Application's own logcat mirror, RuleEngine's free-form rule
        // authoring warnings (DataModule) and the per-row contact skip in
        // ContactSuggestions (developer noise).
        val converted =
            listOf(
                "receiver/SmsReceiver.kt",
                "receiver/SmsSentReceiver.kt",
                "notification/IncomingMessageRouter.kt",
                "data/repository/MessageRepositoryImpl.kt",
                "sms/SystemSmsImporter.kt",
                "work/InitialSyncWorker.kt",
                "work/CatchUpSyncScheduler.kt",
                "work/SimBackfillWorker.kt",
                "sms/ProviderSentTimeSource.kt",
                "data/db/BackfillMessageDirections.kt",
                "data/rules/BundledRuleLoader.kt",
                "work/RecategorizeWorker.kt",
                "work/AutoResortScheduler.kt",
                "data/backup/BackupManager.kt",
                "work/BackupWorker.kt",
                "work/BackupDocumentStore.kt",
                "di/PlatformModule.kt",
                "mms/MmsSender.kt",
                "mms/MmsGateway.kt",
                "mms/MmsInbound.kt",
                "mms/MmsDownloader.kt",
                "mms/OutgoingAttachmentStager.kt",
                "receiver/MmsSentReceiver.kt",
                "receiver/MmsDownloadReceiver.kt",
                "receiver/MmsWapPushReceiver.kt",
                "sms/TelephonyWriter.kt",
                "sms/SystemSentSmsSource.kt",
                "sms/ProviderSimSource.kt",
                "sms/ContactsSource.kt",
                "sms/SmsSender.kt",
                "notification/MessageActionReceiver.kt",
                "data/senderid/SenderIdStore.kt",
            )
        converted.forEach { path ->
            val text = File("src/main/kotlin/app/clearsms", path).readText()
            assertWithMessage(path).that(text).doesNotContain("import android.util.Log")
            assertWithMessage(path).that(text).contains("Diag.")
        }
    }
}
