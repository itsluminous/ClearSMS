package app.clearsms.ui.conversation

import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import app.clearsms.ui.components.DialableNumber

/**
 * What tapping the sender NAME in the conversation top bar does (issue #5).
 *
 * The tap is never a silent no-op: a dialable sender opens their saved
 * contact (or offers to create one), and a service sender - an alphanumeric
 * TRAI id or short code with no phone number behind it - gets a snackbar
 * explaining exactly that. The dialability judgement is [DialableNumber],
 * the same rule message-body links use, so a sender the body would not
 * link as a phone number never offers a contact either.
 */
object SenderContactAction {
    /** The classified outcome of a name tap. */
    sealed interface Action {
        /** A saved contact exists: view it (ACTION_VIEW on its lookup URI). */
        data class ViewContact(
            val lookupUri: String,
        ) : Action

        /** Dialable but unsaved: offer to create/add (ACTION_INSERT_OR_EDIT). */
        data class CreateContact(
            val number: String,
        ) : Action

        /** No phone number behind this sender: explain via snackbar. */
        data object ExplainServiceSender : Action
    }

    /**
     * Classifies a tap on [address]. [contactLookupUri] is the saved
     * contact's lookup URI when one resolved (see `ContactsSource`), null
     * otherwise.
     */
    fun onNameTap(
        address: String,
        contactLookupUri: String?,
    ): Action {
        val number = DialableNumber.of(address) ?: return Action.ExplainServiceSender
        return contactLookupUri?.let { Action.ViewContact(it) } ?: Action.CreateContact(number)
    }

    /**
     * The intent an [Action] fires, or null for the explanation case (the
     * screen shows a snackbar instead). Pure mapping so tests can assert
     * the exact intents. INSERT_OR_EDIT (not INSERT) deliberately: it lets
     * the user add the number to an EXISTING contact as well as create a
     * new one. Nothing here ever uses ACTION_CALL - the call button dials
     * through `ExternalLinks` (tel: -> ACTION_DIAL), which cannot place a
     * call by itself.
     */
    fun intent(action: Action): Intent? =
        when (action) {
            is Action.ViewContact -> Intent(Intent.ACTION_VIEW, Uri.parse(action.lookupUri))
            is Action.CreateContact ->
                Intent(Intent.ACTION_INSERT_OR_EDIT).apply {
                    type = ContactsContract.Contacts.CONTENT_ITEM_TYPE
                    putExtra(ContactsContract.Intents.Insert.PHONE, action.number)
                }
            Action.ExplainServiceSender -> null
        }
}
