package app.clearsms.ui.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Source contract for the KEYBOARD (IME) inset - issue #28's "the keyboard
 * covers the field you are typing in".
 *
 * [app.clearsms.MainActivity] calls enableEdgeToEdge, which makes the
 * manifest's adjustResize inert: the window never shrinks for the keyboard,
 * so Compose must apply WindowInsets.ime itself. The convention:
 *
 * 1. The shell's NavHost is the SINGLE shared owner of the IME inset - it
 *    applies imePadding for every route, so any screen (present or future)
 *    hosted in the graph gets its viewport ended at the keyboard top for
 *    free, and a scrollable form can bring its focused field into view.
 * 2. The ONLY exemption is [Routes.imeSelfManaged] - the two composer
 *    routes, where the shared MessageComposerBar already pads by the live
 *    ime/nav-bar union from the screen scaffold's bottomBar. Padding those
 *    routes in the shell TOO would hoist the composer a keyboard height
 *    above the IME - the doubled-inset band v0.18.2 just spent a release
 *    removing.
 * 3. No screen sprinkles its own imePadding: one owner, or a second
 *    application doubles the inset somewhere.
 * 4. Every file declaring a text field must be a screen the shell hosts
 *    inside that padded NavHost (or the sanctioned composer bar), so a new
 *    text-input surface cannot silently sit under the keyboard.
 *
 * Fields inside AlertDialogs (block list, signature, transaction note) live
 * in their OWN windows where decorFitsSystemWindows stays true, so the
 * framework still resizes/pans them above the keyboard - they need, and get,
 * nothing from the shell.
 */
class ImeInsetOwnershipConventionTest {
    private val srcRoot = File("src/main/kotlin/app/clearsms")

    private fun source(path: String): String = File(srcRoot, path).readText()

    /** Call sites of a Material/foundation text field (not TextFieldValue etc). */
    private val textFieldCall = Regex("""(OutlinedTextField|BasicTextField|[^.\w]TextField)\s*\(""")

    @Test
    fun `the shell NavHost is the single shared owner of the keyboard inset`() {
        val shell = source("ui/navigation/ClearSmsApp.kt")
        // Without this, edge-to-edge leaves every screen's content under the
        // open keyboard (adjustResize is inert once enableEdgeToEdge runs).
        assertThat(shell).contains("Modifier.imePadding()")
        // ...and the exemption must be the declared route set, not ad-hoc.
        assertThat(shell).contains("currentRoute in Routes.imeSelfManaged")
    }

    @Test
    fun `only the composer routes are exempt and they delegate to the ime-owning bar`() {
        val routes = source("ui/navigation/Routes.kt")
        // The exemption list is exactly the two screens whose shared bar
        // self-manages the IME; growing it without a composer reintroduces
        // the covered-field bug on that route.
        assertThat(routes).contains("val imeSelfManaged = setOf(CONVERSATION, COMPOSE)")
        for (screen in listOf("ui/conversation/ConversationScreen.kt", "ui/composemsg/ComposeMessageScreen.kt")) {
            assertThat(source(screen)).contains("MessageComposerBar(")
        }
        // The bar really does own the inset on those routes (live union of
        // keyboard and nav bar - also pinned by ComposerExpansionConventionTest).
        assertThat(source("ui/components/MessageComposerBar.kt"))
            .contains(".windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))")
    }

    @Test
    fun `no screen applies its own imePadding - one owner, never two`() {
        // A per-screen imePadding on top of the shell's is a doubled inset:
        // exactly the band-above-the-composer regression 8296890 removed for
        // the system bars.
        val offenders =
            File(srcRoot, "ui")
                .walkTopDown()
                .filter { it.extension == "kt" }
                .filter { it.readText().contains("imePadding(") }
                .map { it.relativeTo(srcRoot).path }
                .toList()
        assertThat(offenders).containsExactly("ui/navigation/ClearSmsApp.kt")
    }

    @Test
    fun `every text field lives on a screen the shell hosts inside the padded NavHost`() {
        // Source-level containment proxy: a file declaring a text field must
        // either be the sanctioned composer component or be a screen whose
        // composable the shell invokes inside its ime-padded NavHost. A new
        // text-input file that is neither fails here until it is wired
        // through the shell (where the padding is automatic).
        val composerFiles = setOf("ui/components/MessageComposerBar.kt")
        val shell = source("ui/navigation/ClearSmsApp.kt")
        val fieldFiles =
            File(srcRoot, "ui")
                .walkTopDown()
                .filter { it.extension == "kt" }
                .filter { textFieldCall.containsMatchIn(it.readText()) }
                .map { it.relativeTo(srcRoot).path }
                .toList()
        assertThat(fieldFiles).isNotEmpty()
        for (path in fieldFiles) {
            if (path in composerFiles) continue
            val screenComposable = File(path).nameWithoutExtension
            assertThat(shell).contains("$screenComposable(")
        }
    }
}
