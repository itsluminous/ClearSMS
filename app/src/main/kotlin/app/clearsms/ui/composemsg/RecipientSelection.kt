package app.clearsms.ui.composemsg

/**
 * State of the compose recipient field, separating what the user SEES from
 * what is SENT.
 *
 * After picking a contact suggestion, [picked] carries the contact (name,
 * exact chosen number, photo) so the field renders the NAME with the number
 * as secondary text - while [destination] always holds the raw phone number
 * that is actually dialed by the SMS layer. A contact with several numbers
 * yields one suggestion per number, so [picked] records precisely which one
 * was chosen. Manual edits drop the pick and fall back to raw-number entry.
 *
 * Pure so the display-name-vs-sent-number contract is unit-testable.
 */
data class RecipientSelection(
    /** The value the message is sent to: a raw typed string or the picked number. */
    val destination: String = "",
    /** The chosen contact, when the destination came from a suggestion. */
    val picked: ContactSuggestion? = null,
) {
    /** Manual typing always clears any previous pick - raw entry keeps working. */
    fun edit(value: String): RecipientSelection = RecipientSelection(destination = value)

    /** Picking a suggestion: send to its exact number, display its name. */
    fun pick(suggestion: ContactSuggestion): RecipientSelection = RecipientSelection(destination = suggestion.number, picked = suggestion)

    /** Removes the pick and empties the field for a fresh entry. */
    fun clear(): RecipientSelection = RecipientSelection()

    /** The contact name to display instead of the number, when picked. */
    val displayName: String? get() = picked?.name
}
