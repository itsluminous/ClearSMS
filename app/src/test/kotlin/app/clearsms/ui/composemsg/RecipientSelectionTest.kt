package app.clearsms.ui.composemsg

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The recipient field shows the contact NAME after a pick, while the value
 * actually sent stays the phone number — including contacts with several
 * numbers, and without breaking manual raw-number entry.
 */
class RecipientSelectionTest {
    private val ashaMobile = ContactSuggestion(name = "Asha Rao", number = "+919876543210", photoUri = "content://p/1")
    private val ashaWork = ContactSuggestion(name = "Asha Rao", number = "+912212345678")

    @Test
    fun `picking a suggestion displays the name but sends the number`() {
        val selection = RecipientSelection().pick(ashaMobile)
        assertThat(selection.displayName).isEqualTo("Asha Rao")
        assertThat(selection.destination).isEqualTo("+919876543210")
    }

    @Test
    fun `a contact with multiple numbers records exactly which one was chosen`() {
        val selection = RecipientSelection().pick(ashaWork)
        assertThat(selection.displayName).isEqualTo("Asha Rao")
        assertThat(selection.destination).isEqualTo("+912212345678")
        assertThat(selection.picked?.number).isEqualTo("+912212345678")
    }

    @Test
    fun `manual raw number entry keeps working with no display name`() {
        val selection = RecipientSelection().edit("98765").edit("9876543210")
        assertThat(selection.displayName).isNull()
        assertThat(selection.destination).isEqualTo("9876543210")
    }

    @Test
    fun `editing after a pick returns to manual entry`() {
        val selection = RecipientSelection().pick(ashaMobile).edit("98")
        assertThat(selection.picked).isNull()
        assertThat(selection.destination).isEqualTo("98")
    }

    @Test
    fun `clearing a pick empties the field`() {
        val selection = RecipientSelection().pick(ashaMobile).clear()
        assertThat(selection.picked).isNull()
        assertThat(selection.destination).isEmpty()
    }

    @Test
    fun `pick carries the contact photo for the avatar`() {
        val selection = RecipientSelection().pick(ashaMobile)
        assertThat(selection.picked?.photoUri).isEqualTo("content://p/1")
    }
}
