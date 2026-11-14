package app.clearsms.ui.components

import app.clearsms.testing.DefaultStrings
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Source contract for insert-a-contact-as-text (GitHub #150).
 *
 * 1. The text reaches the compose field through the bar's OWN
 *    `TextFieldState` ([ComposerInsertions] -> [ContactInsert.insertAtCursor]),
 *    never by appending to the ViewModel draft: the field adopts the
 *    ViewModel's draft only across an empty boundary, so that route would be
 *    silently dropped while the field holds text.
 * 2. The "+" is a TEXT-capable affordance now, so both screens offer it
 *    unconditionally; MMS availability (issue #94) gates the sheet's
 *    attachment rows instead.
 * 3. The picker is the system `ACTION_PICK`, no vCard, no new permission.
 */
class ContactInsertContractTest {
    private val srcRoot = File("src/main/kotlin/app/clearsms")

    private fun source(path: String): String = File(srcRoot, path).readText()

    private val screens =
        listOf(
            "ui/conversation/ConversationScreen.kt",
            "ui/composemsg/ComposeMessageScreen.kt",
        )

    @Test
    fun `picked text lands on the bar's TextFieldState at the cursor, never on the draft`() {
        val bar = source("ui/components/MessageComposerBar.kt")
        assertThat(bar).contains("insertions: ComposerInsertions? = null")
        assertThat(bar).contains("insertions?.requestFlow?.collect { text -> ContactInsert.insertAtCursor(textState, text) }")
        // The adoption gate is untouched: the insert path bypasses it by
        // editing the field state directly.
        assertThat(bar).contains(
            "ComposerExpansion.shouldAdoptExternalDraft(external = draft, field = textState.text.toString())",
        )
        val insert = source("ui/components/ContactInsert.kt")
        // At the cursor, replacing a selection, cursor left after the text.
        assertThat(insert).contains("replace(start, end, inserted)")
        assertThat(insert).contains("selection = TextRange(start + inserted.length)")
        // No screen or launcher ever feeds picked text to the draft setter.
        for (screen in screens) {
            val text = source(screen)
            assertThat(text).contains("rememberComposerInsertions()")
            assertThat(text).contains("rememberContactInsertLauncher(onInsert = composerInsertions::insert)")
            assertThat(text).contains("insertions = composerInsertions,")
        }
    }

    @Test
    fun `the plus is offered whether or not MMS is available - MMS gates the sheet's attachment rows`() {
        for (screen in screens) {
            val text = source(screen)
            assertThat(text).contains("onAttachClick = { showAttachmentSheet = true },")
            assertThat(text).doesNotContain("if (mmsAvailable) ({ showAttachmentSheet = true }) else null")
            assertThat(text).contains("mmsAvailable = mmsAvailable,")
            assertThat(text).contains("onInsertContact = pickContactForInsert,")
        }
        val sheet = source("ui/components/ComposerAttachments.kt")
        val body = sheet.substringAfter("fun AttachmentPickerSheet(").substringBefore("private fun SheetOption(")
        assertThat(body).contains("mmsAvailable: Boolean")
        // Photo / Camera / Any file inside the MMS gate...
        val gated = body.substringAfter("if (mmsAvailable) {").substringBefore("HorizontalDivider")
        assertThat(gated).contains("compose_attach_photo")
        assertThat(gated).contains("compose_attach_camera")
        assertThat(gated).contains("compose_attach_file")
        // ...and the Contact row after it, outside the gate.
        val contact = body.substringAfter("HorizontalDivider")
        assertThat(contact).contains("R.string.compose_attach_contact")
        assertThat(contact).contains("onInsertContact()")
        assertThat(contact).doesNotContain("mmsAvailable")
    }

    @Test
    fun `the picker is the system one, the format is a localised template, and no vCard or permission is added`() {
        val insert = source("ui/components/ContactInsert.kt")
        assertThat(insert).contains("Intent(Intent.ACTION_PICK, Phone.CONTENT_URI)")
        assertThat(insert).contains("val template = stringResource(R.string.compose_contact_text)")
        assertThat(insert).contains("String.format(locale, template, name, number)")
        // A missing picker or an unreadable row ends in a toast, not a crash.
        assertThat(insert).contains("catch (_: ActivityNotFoundException)")
        assertThat(insert).contains("compose_contact_picker_missing")
        assertThat(insert).contains("compose_contact_unreadable")
        // Plain text only.
        for (file in listOf(insert, source("ui/components/ComposerAttachments.kt"))) {
            assertThat(file).doesNotContain("text/vcard")
            assertThat(file).doesNotContain("text/x-vcard")
            assertThat(file).doesNotContain("StagedAttachment(")
        }
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertThat(manifest).doesNotContain("WRITE_CONTACTS")
        // The template has both positional arguments, so it can be reordered.
        val platform = DefaultStrings.platform
        assertThat(platform).contains("""<string name="compose_contact_text">%1${'$'}s: %2${'$'}s</string>""")
        assertThat(platform).contains("\"compose_attach_contact\"")
        assertThat(platform).contains("\"compose_attach_contact_hint\"")
        // The "+" label no longer promises an attachment only.
        assertThat(platform).contains("""<string name="compose_attach">Add to message</string>""")
    }
}
