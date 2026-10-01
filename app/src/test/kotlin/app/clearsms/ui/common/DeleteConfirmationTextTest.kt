package app.clearsms.ui.common

import app.clearsms.R
import app.clearsms.data.repository.UndoManager
import app.clearsms.ui.common.DeleteConfirmationText.Target
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The delete dialog must promise exactly what the repository then does:
 * with the recycle bin on (the default) a delete is restorable for 30 days,
 * so it must not be called permanent; with the bin off it is permanent and
 * the dialog must keep saying so, including the system-SMS-store part.
 */
class DeleteConfirmationTextTest {
    private val selection = File("src/main/res/values/strings_selection.xml").readText()

    private fun string(name: String): String =
        Regex("""<string name="$name">(.*?)</string>""")
            .find(selection)
            ?.groupValues
            ?.get(1)
            ?.replace("\\'", "'")
            ?: error("missing string $name")

    private val resourceName =
        mapOf(
            R.string.selection_delete_messages_message to "selection_delete_messages_message",
            R.string.selection_delete_messages_to_bin_message to "selection_delete_messages_to_bin_message",
            R.string.selection_delete_threads_message to "selection_delete_threads_message",
            R.string.selection_delete_threads_to_bin_message to "selection_delete_threads_to_bin_message",
        )

    private fun body(
        target: Target,
        binEnabled: Boolean,
        count: Int,
    ): String {
        val res = DeleteConfirmationText.bodyRes(target, binEnabled)
        val name = resourceName[res] ?: error("bodyRes returned a resource outside the delete-dialog set")
        return string(name).replace("%1\$d", count.toString())
    }

    @Test
    fun `single message with the bin on is restorable, never permanent`() {
        val text = body(Target.MESSAGES, binEnabled = true, count = 1)
        assertThat(text).startsWith("1 message(s) will move to the recycle bin")
        assertThat(text).contains("restore")
        assertThat(text).contains("30 days")
        assertThat(text).doesNotContain("permanently")
        assertThat(text).doesNotContain("can't be undone")
        assertThat(text).doesNotContain("cannot be undone")
    }

    @Test
    fun `single message with the bin off is permanent and names the SMS store`() {
        val text = body(Target.MESSAGES, binEnabled = false, count = 1)
        assertThat(text).isEqualTo(
            "1 message(s) will be permanently deleted, including from the system SMS store. This can't be undone.",
        )
    }

    @Test
    fun `multiple conversations with the bin on are restorable, never permanent`() {
        val text = body(Target.CONVERSATIONS, binEnabled = true, count = 7)
        assertThat(text).startsWith("7 conversation(s) will move to the recycle bin")
        assertThat(text).contains("restore")
        assertThat(text).contains("30 days")
        assertThat(text).doesNotContain("permanently")
        assertThat(text).doesNotContain("can't be undone")
        assertThat(text).doesNotContain("cannot be undone")
    }

    @Test
    fun `multiple conversations with the bin off are permanent and name the SMS store`() {
        val text = body(Target.CONVERSATIONS, binEnabled = false, count = 7)
        assertThat(text).isEqualTo(
            "7 conversation(s) will be permanently deleted, including from the system SMS store. This can't be undone.",
        )
    }

    @Test
    fun `archived per-row delete is the single-conversation case of the same decision`() {
        // ArchivedScreen's row dialog formats the CONVERSATIONS body with 1;
        // it must flip with the bin exactly like the selection dialog does.
        assertThat(body(Target.CONVERSATIONS, binEnabled = true, count = 1))
            .startsWith("1 conversation(s) will move to the recycle bin")
        assertThat(body(Target.CONVERSATIONS, binEnabled = false, count = 1))
            .startsWith("1 conversation(s) will be permanently deleted")
        assertThat(DeleteConfirmationText.bodyRes(Target.CONVERSATIONS, recycleBinEnabled = true))
            .isEqualTo(R.string.selection_delete_threads_to_bin_message)
        assertThat(DeleteConfirmationText.bodyRes(Target.CONVERSATIONS, recycleBinEnabled = false))
            .isEqualTo(R.string.selection_delete_threads_message)
    }

    @Test
    fun `bin wording still tells the user the provider copy leaves until restored`() {
        // commitStagedDelete(toBin = true) deletes the provider row; a restore
        // writes a fresh one. The dialog says so rather than implying the
        // message stays visible to other SMS apps meanwhile.
        for (target in Target.entries) {
            assertThat(body(target, binEnabled = true, count = 2)).contains("phone's SMS store")
        }
    }

    @Test
    fun `the retention the dialog names is the bin's real retention`() {
        val days = TimeUnit.MILLISECONDS.toDays(UndoManager.BIN_RETENTION_MS)
        for (target in Target.entries) {
            assertThat(body(target, binEnabled = true, count = 1)).contains("$days days")
        }
    }
}
