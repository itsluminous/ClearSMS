package app.clearsms.ui.common

import androidx.annotation.StringRes
import app.clearsms.R

/**
 * The ONE decision of what a delete-confirmation dialog promises, shared by
 * the inbox, archived and conversation screens so they can never disagree
 * with each other - or with what the repository then does.
 *
 * Every delete those screens offer is staged through
 * [app.clearsms.data.repository.UndoManager] and committed with
 * `toBin = recycleBinEnabled()`. With the bin ON (the default) the rows rest
 * in the bin for [app.clearsms.data.repository.UndoManager.BIN_RETENTION_MS]
 * and can be restored, so telling the user the delete "can't be undone" is
 * false; the provider copy IS removed at commit (a restore writes a fresh
 * one), so the bin wording still says the message leaves the phone's SMS
 * store. With the bin OFF the rows are deleted outright, provider copy
 * included, and the permanent wording is the honest one.
 *
 * Deliberately NOT routed through here: the bin's own Delete forever / Empty
 * bin dialogs (they act on the bin, so they really are permanent), the
 * Settings OTP clear (`MessageRepository.deleteOtpOlderThan` hard-deletes,
 * bypassing the bin) and the Alerts "Clear older" (reminder rows, deleted
 * outright). Each of those keeps its permanent wording on purpose.
 */
object DeleteConfirmationText {
    /** What the rows being deleted are, which picks the noun in the body. */
    enum class Target {
        /** Individual messages selected inside a conversation. */
        MESSAGES,

        /** Whole conversations (inbox and archived rows). */
        CONVERSATIONS,
    }

    /**
     * The dialog body for deleting [target] rows when the recycle bin is
     * [recycleBinEnabled]. The resource takes the row count as `%1$d`.
     */
    @StringRes
    fun bodyRes(
        target: Target,
        recycleBinEnabled: Boolean,
    ): Int =
        when (target) {
            Target.MESSAGES ->
                if (recycleBinEnabled) {
                    R.string.selection_delete_messages_to_bin_message
                } else {
                    R.string.selection_delete_messages_message
                }
            Target.CONVERSATIONS ->
                if (recycleBinEnabled) {
                    R.string.selection_delete_threads_to_bin_message
                } else {
                    R.string.selection_delete_threads_message
                }
        }
}
