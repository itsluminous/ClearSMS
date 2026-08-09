package app.clearsms.ui.common

/**
 * One-shot "N deleted/archived" snackbar request with an UNDO action,
 * emitted by the inbox / conversation / archived view-models after a
 * destructive action is staged in the shared
 * [app.clearsms.data.repository.UndoManager].
 */
sealed interface UndoUiEvent {
    val count: Int

    data class Deleted(
        override val count: Int,
    ) : UndoUiEvent

    data class Archived(
        override val count: Int,
    ) : UndoUiEvent
}
