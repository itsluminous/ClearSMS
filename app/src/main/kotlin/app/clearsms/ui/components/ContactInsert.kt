package app.clearsms.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import app.clearsms.R
import app.clearsms.ui.common.rememberDisplayLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Contact" in the compose bar's add sheet (GitHub #150): pick a contact's
 * phone number and insert `Name: number` into the message as PLAIN TEXT -
 * never a vCard, never an MMS part, so it works wherever SMS works.
 *
 * The picker is the system's own `ACTION_PICK` on the phone-number table
 * ([Phone.CONTENT_URI]), not a list of our own: one row of the result IS
 * one number, so a contact with several numbers is resolved in the system
 * UI with no second query and no dialog here; and the result URI carries a
 * read grant, so the pick works even if READ_CONTACTS were denied. The
 * stock `PickContact` contract returns a contact URI and would leave the
 * several-numbers choice to us - hence this 10-line contract.
 */
class PickPhoneNumber : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(
        context: Context,
        input: Unit,
    ): Intent = Intent(Intent.ACTION_PICK, Phone.CONTENT_URI)

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/** A picked phone-number row, as the provider returned it; either field may be blank. */
data class PickedPhone(
    val name: String?,
    val number: String?,
)

object ContactInsert {
    /** The one row behind a [PickPhoneNumber] result; null when the provider has nothing for it. */
    fun read(
        resolver: ContentResolver,
        uri: Uri,
    ): PickedPhone? =
        resolver
            .query(uri, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                PickedPhone(name = cursor.getString(0), number = cursor.getString(1))
            }

    /**
     * The text that goes into the message, pure so the degenerate rows are
     * unit-tested: a nameless row (the provider shows the number as the
     * name, or no name at all) inserts the bare number rather than
     * ": +91…" or "+91…: +91…"; a row with a name but no number inserts
     * the name; an empty row inserts nothing (null). Both parts are
     * trimmed, the number is otherwise exactly what the provider holds -
     * the user chose that formatting in their contacts app. [template]
     * joins the two from a localised format string, so a translator can
     * reorder name and number or change the separator.
     */
    fun format(
        row: PickedPhone?,
        template: (name: String, number: String) -> String,
    ): String? {
        val name = row?.name?.trim().orEmpty()
        val number = row?.number?.trim().orEmpty()
        return when {
            name.isEmpty() && number.isEmpty() -> null
            number.isEmpty() -> name
            name.isEmpty() || name == number -> number
            else -> template(name, number)
        }
    }

    /**
     * Where picked text lands in the compose field: AT THE CURSOR, replacing
     * any selection, with the cursor left after it - never appended to the
     * end of a draft the user is editing in the middle of. A space is put
     * before it when the character under the cursor's left is not already
     * whitespace ("call" + pick -> "call Ravi Kumar: …", not "callRavi…"),
     * and nothing when the field is empty or the cursor follows a space or
     * newline. Pure on the [TextFieldState], so the rule is unit-tested
     * without a UI harness.
     */
    fun insertAtCursor(
        state: TextFieldState,
        text: String,
    ) {
        if (text.isEmpty()) return
        state.edit {
            val start = selection.min
            val end = selection.max
            val inserted = withSeparator(before = asCharSequence().subSequence(0, start), text = text)
            replace(start, end, inserted)
            selection = TextRange(start + inserted.length)
        }
    }

    /** The separator rule of [insertAtCursor], on its own for the tests. */
    fun withSeparator(
        before: CharSequence,
        text: String,
    ): String = if (before.isEmpty() || before.last().isWhitespace()) text else " $text"
}

/**
 * The route by which text that was NOT typed reaches the compose field.
 *
 * The field's text lives in a `TextFieldState` inside [MessageComposerBar],
 * and the ViewModel's String draft is adopted by the field only across an
 * EMPTY boundary (the bar's `shouldAdoptExternalDraft` gate) - so
 * appending a contact to the ViewModel's draft would be silently dropped
 * while the field holds text, or would clobber the cursor. Instead the
 * screen hands the bar one of these; the bar applies each request straight
 * to its own `TextFieldState` ([ContactInsert.insertAtCursor]), and the
 * field's normal field-to-ViewModel sync then carries the new text into the
 * persisted draft like anything typed. Requests are buffered, so a pick that
 * returns while the bar is between compositions is not lost.
 */
class ComposerInsertions {
    private val requests = Channel<String>(Channel.UNLIMITED)

    /** Insert [text] at the compose field's cursor. */
    fun insert(text: String) {
        if (text.isNotEmpty()) requests.trySend(text)
    }

    internal val requestFlow: Flow<String> = requests.receiveAsFlow()
}

@Composable
fun rememberComposerInsertions(): ComposerInsertions = remember { ComposerInsertions() }

/**
 * The launcher behind the sheet's "Contact" row: opens [PickPhoneNumber],
 * reads the picked row off the main thread, formats it through the
 * localised `compose_contact_text` and hands the text to [onInsert]. An OEM
 * with no contact picker, or a row the provider cannot read, ends in a
 * toast - never a crash and never a half-inserted string.
 */
@Composable
fun rememberContactInsertLauncher(onInsert: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnInsert by rememberUpdatedState(onInsert)
    val unreadable = stringResource(R.string.compose_contact_unreadable)
    val pickerMissing = stringResource(R.string.compose_contact_picker_missing)
    // The template is read in composition (so it follows the UI language)
    // and applied in the result callback, in the display locale, exactly as
    // stringResource(id, args) would.
    val template = stringResource(R.string.compose_contact_text)
    val locale = rememberDisplayLocale()
    val picker =
        rememberLauncherForActivityResult(PickPhoneNumber()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val row = withContext(Dispatchers.IO) { ContactInsert.read(context.contentResolver, uri) }
                val text =
                    ContactInsert.format(row) { name, number ->
                        String.format(locale, template, name, number)
                    }
                if (text == null) {
                    Toast.makeText(context, unreadable, Toast.LENGTH_SHORT).show()
                } else {
                    currentOnInsert(text)
                }
            }
        }
    return {
        try {
            picker.launch(Unit)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, pickerMissing, Toast.LENGTH_SHORT).show()
        }
    }
}
