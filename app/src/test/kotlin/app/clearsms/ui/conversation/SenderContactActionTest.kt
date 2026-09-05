package app.clearsms.ui.conversation

import android.content.Intent
import android.provider.ContactsContract
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The conversation top bar's name tap (issue #5): view the contact, offer to
 * create it, or - for service senders - explain instead of no-opping. The
 * reporter's complaint was precisely a silent no-op, so the classification
 * must NEVER produce "do nothing" for any input.
 */
@RunWith(RobolectricTestRunner::class)
class SenderContactActionTest {
    @Test
    fun `saved contact opens via its lookup uri`() {
        val lookup = "content://com.android.contacts/contacts/lookup/abc/12"
        val action = SenderContactAction.onNameTap("9876543210", lookup)
        assertThat(action).isEqualTo(SenderContactAction.Action.ViewContact(lookup))
        val intent = SenderContactAction.intent(action)!!
        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.data.toString()).isEqualTo(lookup)
    }

    @Test
    fun `dialable sender without a contact offers to create one`() {
        val action = SenderContactAction.onNameTap("+919876543210", null)
        assertThat(action).isEqualTo(SenderContactAction.Action.CreateContact("+919876543210"))
        val intent = SenderContactAction.intent(action)!!
        // INSERT_OR_EDIT (not INSERT): the picker also allows adding the
        // number to an existing contact.
        assertThat(intent.action).isEqualTo(Intent.ACTION_INSERT_OR_EDIT)
        assertThat(intent.type).isEqualTo(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
        assertThat(intent.getStringExtra(ContactsContract.Intents.Insert.PHONE))
            .isEqualTo("+919876543210")
    }

    @Test
    fun `alphanumeric sender id explains itself`() {
        val action = SenderContactAction.onNameTap("VM-HDFCBK", null)
        assertThat(action).isEqualTo(SenderContactAction.Action.ExplainServiceSender)
        // No intent: the screen shows the service-sender snackbar instead.
        assertThat(SenderContactAction.intent(action)).isNull()
    }

    @Test
    fun `short code explains itself even with a stale lookup uri`() {
        // A short code has no phone number regardless of what a lookup
        // returned: dialability is checked FIRST.
        val action = SenderContactAction.onNameTap("139", "content://stale")
        assertThat(action).isEqualTo(SenderContactAction.Action.ExplainServiceSender)
    }

    @Test
    fun `no action ever places a call by itself`() {
        // ACTION_CALL needs CALL_PHONE and dials unprompted - the contract
        // for numbers that arrived in an SMS is ACTION_DIAL/VIEW/INSERT only.
        listOf(
            SenderContactAction.onNameTap("9876543210", "content://c/1"),
            SenderContactAction.onNameTap("9876543210", null),
            SenderContactAction.onNameTap("HDFCBK", null),
        ).forEach { action ->
            assertThat(SenderContactAction.intent(action)?.action).isNotEqualTo(Intent.ACTION_CALL)
        }
    }
}
