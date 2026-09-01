package app.clearsms.ui.inbox

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * The recycle bin's row shows two truncated lines, which is rarely enough to
 * choose between restoring a message and deleting it forever. Tapping a row
 * opens the whole message, with both decisions on the dialog.
 *
 * Source-level, in the spirit of the other convention tests here: the project
 * has no Compose UI test harness, and adding Robolectric gesture tests would
 * invite back the CI flakiness this repo has already fought once.
 */
class BinPreviewConventionTest {
    private val source =
        File(
            listOf("src/main/kotlin/app/clearsms", "app/src/main/kotlin/app/clearsms").first { File(it).isDirectory },
            "ui/inbox/BinScreen.kt",
        ).readText()

    @Test
    fun `a bin row is tappable and opens the preview`() {
        assertWithMessage("the row must be clickable")
            .that(source)
            .contains("Modifier.clickable(onClick = onPreview)")
        assertWithMessage("the tap must set the previewed item")
            .that(source)
            .contains("onPreview = { previewItem = item }")
    }

    @Test
    fun `the preview offers restore and delete forever`() {
        // The preview exists to serve a decision, so both outcomes are on it.
        assertWithMessage("restore must be reachable from the preview")
            .that(source)
            .contains("viewModel.restore(message.id)")
        assertWithMessage("delete forever must be reachable from the preview")
            .that(source)
            .contains("confirmDeleteForever = message.id")
    }

    @Test
    fun `deleting forever from the preview still goes through the confirmation`() {
        // The preview must not become a shortcut around the irreversible-action
        // confirmation: it hands off to the same confirm dialog the row does.
        val previewDeletesDirectly = source.contains("viewModel.deleteForever(message.id)")
        assertWithMessage("the preview must not delete without confirmation")
            .that(previewDeletesDirectly)
            .isFalse()
    }

    @Test
    fun `the preview shows the whole body, scrollable`() {
        assertWithMessage("a long binned message must scroll rather than clip")
            .that(source)
            .contains("verticalScroll(rememberScrollState())")
        assertWithMessage("the body is shown in full, not the truncated row text")
            .that(source)
            .contains("Text(text = message.body, style = MaterialTheme.typography.bodyMedium)")
    }

    @Test
    fun `the preview does not linkify the body`() {
        // A stray tap opening a link - in what may be a blocked sender's
        // message - would be a surprise in a screen about deleting things.
        assertWithMessage("bin previews stay plain text")
            .that(source)
            .doesNotContain("LinkifiedBodyText")
    }
}
