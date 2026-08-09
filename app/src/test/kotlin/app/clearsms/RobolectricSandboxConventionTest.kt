package app.clearsms

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Guards against Robolectric sandbox splitting in the unit-test suite.
 *
 * WHY: Robolectric creates one sandbox classloader per distinct test
 * configuration - every `@Config(sdk = ...)`, `@Config(shadows = ...)`,
 * `@Config(qualifiers = ...)`, `@GraphicsMode` or `@LooperMode` value (on a
 * class, superclass or method, or via a robolectric.properties file) forks
 * a new sandbox. Robolectric's DefaultNativeRuntimeLoader guards native
 * graphics loading with a PER-CLASSLOADER static, but its font extraction
 * opens a zip FileSystem in the JVM-GLOBAL registry and never closes it:
 * when a SECOND sandbox loads native graphics, `FileSystems.newFileSystem`
 * throws FileSystemAlreadyExistsException, the initializer poisons, and
 * every later native-graphics test in that sandbox dies with
 * UnsatisfiedLinkError (this intermittently killed the v0.9.2 and v0.10.0
 * release builds via SenderIconFactoryTest). Robolectric upgrades did not
 * fix it, so the suite must run in EXACTLY ONE sandbox.
 *
 * WHAT TO DO INSTEAD of a splitting annotation:
 * - `@Config(sdk = ...)`: make the SDK level injectable in the code under
 *   test (an `sdkInt: Int = Build.VERSION.SDK_INT` parameter or a small
 *   provider interface) and pass the level explicitly - see OtpClipboard.
 * - `@Config(shadows = ...)`: introduce a thin seam interface over the
 *   framework calls, delegate in production, fake in tests - see
 *   SmsGateway / FakeSmsGateway.
 * - `@Config(qualifiers = ...)`: derive the qualified values through an
 *   injectable resource/locale seam.
 *
 * If a future need is truly irreducible, add the file to [allowlist] with a
 * justification - but expect it to reintroduce the race.
 */
class RobolectricSandboxConventionTest {
    /** Relative paths (from `src/test/kotlin`) permitted to split. Keep empty. */
    private val allowlist = emptySet<String>()

    // Patterns are concatenated so this file does not match itself.
    private val forbidden =
        listOf(
            Regex("""@(org\.robolectric\.annotation\.)?Conf""" + """ig\s*\("""),
            Regex("""@(org\.robolectric\.annotation\.)?Graphics""" + """Mode\b"""),
            Regex("""@(org\.robolectric\.annotation\.)?Looper""" + """Mode\b"""),
        )

    @Test
    fun `no test may fork a second Robolectric sandbox`() {
        val sourceRoot = File("src/test/kotlin")
        assertWithMessage("test must run from the app module").that(sourceRoot.isDirectory).isTrue()
        val violations = mutableListOf<String>()
        sourceRoot
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.toRelativeString(sourceRoot) in allowlist }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    // Annotations are code: doc/comment mentions are fine.
                    val trimmed = line.trim()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                        return@forEachIndexed
                    }
                    if (forbidden.any { it.containsMatchIn(line) }) {
                        violations += "${file.path}:${index + 1} -> ${line.trim()}"
                    }
                }
            }
        assertWithMessage(
            "Sandbox-splitting Robolectric annotations are banned (they re-trigger " +
                "the native-runtime FileSystemAlreadyExistsException race; inject a " +
                "seam instead - see this test's KDoc):\n${violations.joinToString("\n")}",
        ).that(violations)
            .isEmpty()
    }

    @Test
    fun `no robolectric properties file may override the test configuration`() {
        val testRoot = File("src/test")
        assertWithMessage("test must run from the app module").that(testRoot.isDirectory).isTrue()
        val propertiesFiles =
            testRoot
                .walkTopDown()
                .filter { it.isFile && it.name == "robolectric.properties" }
                .map { it.path }
                .toList()
        assertWithMessage(
            "robolectric.properties files set @Config values suite-wide and can " +
                "silently fork sandboxes:\n${propertiesFiles.joinToString("\n")}",
        ).that(propertiesFiles)
            .isEmpty()
    }
}
