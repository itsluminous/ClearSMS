package app.clearsms

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Enforces the logging convention at the source level: no `Log.*` /
 * `println` / `printStackTrace` call may interpolate message bodies, OTP
 * codes, amounts, account numbers or senders/phone numbers. Diagnostics must
 * reference ids, counts and categories only.
 */
class SensitiveLoggingConventionTest {
    /** Interpolations that would leak content into logcat. */
    private val forbidden =
        listOf(
            "body",
            "otp",
            "sender",
            "amount",
            "account",
            "recipient",
            "phone",
            "address",
            "destination",
            "text",
        )

    private val logCall = Regex("""\b(Log\.[vdiwe]|Log\.wtf|println|printStackTrace)\b""")

    // Matches "$name", "${name...}", "$it.name" style interpolations inside the line.
    private fun interpolations(line: String): List<String> =
        Regex("""\$\{?([A-Za-z_][A-Za-z0-9_.]*)""")
            .findAll(line)
            .map { it.groupValues[1].lowercase() }
            .toList()

    @Test
    fun `no log statement interpolates message content or identifiers`() {
        val sourceRoot = File("src/main/kotlin")
        assertWithMessage("test must run from the app module").that(sourceRoot.isDirectory).isTrue()
        val violations = mutableListOf<String>()
        sourceRoot
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    if (!logCall.containsMatchIn(line)) return@forEachIndexed
                    val leaked =
                        interpolations(line).filter { name ->
                            forbidden.any { name == it || name.endsWith(".$it") || name.contains(it) }
                        }
                    if (leaked.isNotEmpty()) {
                        violations += "${file.path}:${index + 1} interpolates $leaked -> $line"
                    }
                }
            }
        assertWithMessage(
            "Log statements must never include message bodies, OTPs, amounts, " +
                "accounts or phone numbers/senders:\n${violations.joinToString("\n")}",
        ).that(violations).isEmpty()
    }
}
