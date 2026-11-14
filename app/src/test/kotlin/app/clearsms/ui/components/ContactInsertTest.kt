package app.clearsms.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ApplicationProvider
import app.clearsms.sms.AccentFold
import app.clearsms.sms.SmsSegments
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Insert a contact as plain text (GitHub #150): the picker contract, the
 * name/number formatting including the degenerate rows, and the
 * at-the-cursor insertion into the compose field's own state. All fixture
 * names and numbers are synthetic.
 */
@RunWith(RobolectricTestRunner::class)
class ContactInsertTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The English `compose_contact_text`, as the launcher applies it. */
    private val template = { name: String, number: String -> "$name: $number" }

    @Test
    fun `the picker is the system ACTION_PICK on the phone-number table`() {
        // One row of Phone.CONTENT_URI IS one number, so a contact with
        // several numbers is resolved in the system UI, not by us.
        val intent = PickPhoneNumber().createIntent(context, Unit)
        assertThat(intent.action).isEqualTo(Intent.ACTION_PICK)
        assertThat(intent.data).isEqualTo(Phone.CONTENT_URI)
    }

    @Test
    fun `the picker result is the row uri, and a cancel is null`() {
        val row = Uri.parse("content://com.android.contacts/data/42")
        val contract = PickPhoneNumber()
        assertThat(contract.parseResult(Activity.RESULT_OK, Intent().setData(row))).isEqualTo(row)
        assertThat(contract.parseResult(Activity.RESULT_CANCELED, Intent().setData(row))).isNull()
        assertThat(contract.parseResult(Activity.RESULT_OK, null)).isNull()
    }

    @Test
    fun `name and number go through the template, trimmed`() {
        val text = ContactInsert.format(PickedPhone(name = " Ravi Kumar ", number = " +91 98765 43210 "), template)
        assertThat(text).isEqualTo("Ravi Kumar: +91 98765 43210")
    }

    @Test
    fun `a nameless row inserts the bare number, never a dangling separator`() {
        assertThat(ContactInsert.format(PickedPhone(name = null, number = "+91 98765 43210"), template))
            .isEqualTo("+91 98765 43210")
        assertThat(ContactInsert.format(PickedPhone(name = "  ", number = "+91 98765 43210"), template))
            .isEqualTo("+91 98765 43210")
        // The provider shows the number AS the name for an unnamed contact.
        assertThat(ContactInsert.format(PickedPhone(name = "+91 98765 43210", number = "+91 98765 43210"), template))
            .isEqualTo("+91 98765 43210")
    }

    @Test
    fun `a row with a name but no number inserts the name, and an empty row nothing`() {
        assertThat(ContactInsert.format(PickedPhone(name = "Ravi Kumar", number = ""), template)).isEqualTo("Ravi Kumar")
        assertThat(ContactInsert.format(PickedPhone(name = null, number = null), template)).isNull()
        assertThat(ContactInsert.format(null, template)).isNull()
    }

    @Test
    fun `odd number formatting is kept as the provider holds it`() {
        // The user chose that formatting in their contacts app; the app
        // does not normalise, strip or re-space it.
        assertThat(ContactInsert.format(PickedPhone("Ravi", "(0)98-765 43.210 ext 7"), template))
            .isEqualTo("Ravi: (0)98-765 43.210 ext 7")
    }

    @Test
    fun `the template controls word order, so a translator can put the number first`() {
        val reordered = ContactInsert.format(PickedPhone("Ravi", "+91 1")) { name, number -> "$number ($name)" }
        assertThat(reordered).isEqualTo("+91 1 (Ravi)")
    }

    @Test
    fun `insertion lands at the cursor and leaves the cursor after the text`() {
        // "Please call  tomorrow" with the cursor between the two spaces.
        val state = TextFieldState("Please call  tomorrow", TextRange(12))
        ContactInsert.insertAtCursor(state, "Ravi: +91 1")
        assertThat(state.text.toString()).isEqualTo("Please call Ravi: +91 1 tomorrow")
        assertThat(state.selection).isEqualTo(TextRange(23))
    }

    @Test
    fun `a selection is replaced, not surrounded`() {
        val state = TextFieldState("Call THEM now", TextRange(5, 9))
        ContactInsert.insertAtCursor(state, "Ravi: +91 1")
        assertThat(state.text.toString()).isEqualTo("Call Ravi: +91 1 now")
        assertThat(state.selection).isEqualTo(TextRange(16))
    }

    @Test
    fun `a space separates the text from a word it would otherwise fuse with`() {
        val state = TextFieldState("call", TextRange(4))
        ContactInsert.insertAtCursor(state, "Ravi: +91 1")
        assertThat(state.text.toString()).isEqualTo("call Ravi: +91 1")
        // No separator into an empty field or after whitespace.
        assertThat(ContactInsert.withSeparator("", "x")).isEqualTo("x")
        assertThat(ContactInsert.withSeparator("line\n", "x")).isEqualTo("x")
        assertThat(ContactInsert.withSeparator("call ", "x")).isEqualTo("x")
        assertThat(ContactInsert.withSeparator("call", "x")).isEqualTo(" x")
    }

    @Test
    fun `picking again inserts another contact after the first`() {
        val state = TextFieldState("", TextRange(0))
        ContactInsert.insertAtCursor(state, "Ravi: +91 1")
        ContactInsert.insertAtCursor(state, "Meera: +91 2")
        assertThat(state.text.toString()).isEqualTo("Ravi: +91 1 Meera: +91 2")
        assertThat(state.selection).isEqualTo(TextRange(state.text.length))
    }

    @Test
    fun `an empty insertion changes nothing`() {
        val state = TextFieldState("abc", TextRange(1))
        ContactInsert.insertAtCursor(state, "")
        assertThat(state.text.toString()).isEqualTo("abc")
        assertThat(state.selection).isEqualTo(TextRange(1))
    }

    @Test
    fun `inserted text is ordinary message text - it counts segments and strips accents like typing`() {
        // Field -> ViewModel -> send: the inserted text is a String in the
        // body, so the billable-segment math and the strip-accents setting
        // see it exactly as typed text. A diacritic in a contact name is the
        // very case the setting exists for.
        val state = TextFieldState("Reach me via ", TextRange(13))
        ContactInsert.insertAtCursor(state, ContactInsert.format(PickedPhone("José Pérez", "+34 600 000 000"), template)!!)
        val body = state.text.toString()
        assertThat(body).isEqualTo("Reach me via José Pérez: +34 600 000 000")
        // é is in the GSM-7 table: one segment, no UCS-2 flip from the name.
        assertThat(SmsSegments.count(body)).isEqualTo(1)
        // A name with a non-GSM letter pushes a long body to UCS-2; the
        // strip-accents setting folds it back when that saves a segment.
        val long = "a".repeat(140) + " " + ContactInsert.format(PickedPhone("Tomáš Dvořák", "+420 1"), template)!!
        assertThat(SmsSegments.count(long)).isEqualTo(3)
        assertThat(AccentFold.foldIfItSaves(long)).endsWith(" Tomas Dvorak: +420 1")
        assertThat(SmsSegments.count(AccentFold.foldIfItSaves(long))).isEqualTo(2)
    }
}
