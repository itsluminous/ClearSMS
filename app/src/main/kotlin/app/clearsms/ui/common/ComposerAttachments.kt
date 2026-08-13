package app.clearsms.ui.common

import android.net.Uri
import app.clearsms.mms.MmsSizeLimits
import app.clearsms.mms.OutgoingAttachmentStager
import app.clearsms.mms.StagedAttachment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Why an attachment could not be added; rendered as the inline error. */
enum class AttachmentError {
    /** Adding it would push the message over the carrier size budget, even after compression. */
    TOO_LARGE,

    /** The content could not be read (revoked grant, vanished document). */
    UNREADABLE,
}

/**
 * Compose-bar attachment state shared by the conversation and
 * new-conversation ViewModels (the same pattern as ConversationDraft):
 * staging, the running size budget, inline errors and removal. NOT
 * persisted: attachment state deliberately does not survive in drafts
 * this wave (text does, as today) - leaving the screen discards the
 * staged files via [discardAll].
 */
class ComposerAttachments(
    private val stager: OutgoingAttachmentStager,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) {
    private val list = MutableStateFlow<List<StagedAttachment>>(emptyList())
    val attachments: StateFlow<List<StagedAttachment>> = list.asStateFlow()

    /** The latest add failure; cleared by the next successful action. */
    private val errorFlow = MutableStateFlow<AttachmentError?>(null)
    val error: StateFlow<AttachmentError?> = errorFlow.asStateFlow()

    /** Camera capture in flight, if any (armed by [cameraUri]). */
    private var pendingCapture: File? = null

    /** Stages every [uris] entry (pickers may return several). */
    fun add(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch(dispatcher) { uris.forEach(::addStaged) }
    }

    /** Arms a camera capture and returns the URI to hand to the camera app. */
    fun cameraUri(): Uri {
        val target = stager.cameraTarget()
        pendingCapture = target
        return stager.cameraUriFor(target)
    }

    /** TakePicture came back; stages the capture on success, cleans up otherwise. */
    fun onCameraResult(success: Boolean) {
        val target = pendingCapture ?: return
        pendingCapture = null
        scope.launch(dispatcher) {
            if (success) {
                accept(stager.stageCameraResult(target))
            } else {
                target.delete()
            }
        }
    }

    /** Removes a chip and deletes its staged file. */
    fun remove(attachment: StagedAttachment) {
        list.value = list.value.filterNot { it.id == attachment.id }
        errorFlow.value = null
        scope.launch(dispatcher) { stager.discard(attachment) }
    }

    /**
     * Hands the staged attachments to a send and clears the compose state.
     * File ownership passes to the caller (the sender moves the bytes into
     * the message's attachment directory and deletes the staged copies).
     */
    fun consume(): List<StagedAttachment> {
        val consumed = list.value
        list.value = emptyList()
        errorFlow.value = null
        return consumed
    }

    /** Discards everything staged (compose abandoned). */
    fun discardAll() {
        val discarded = list.value
        list.value = emptyList()
        errorFlow.value = null
        scope.launch(dispatcher) { discarded.forEach(stager::discard) }
    }

    private fun addStaged(uri: Uri) {
        accept(stager.stage(uri))
    }

    /** Budget gate: an attachment that does not fit is discarded with an inline error. */
    private fun accept(staged: StagedAttachment?) {
        if (staged == null) {
            errorFlow.value = AttachmentError.UNREADABLE
            return
        }
        val current = list.value
        val total = current.sumOf { it.sizeBytes } + staged.sizeBytes
        if (total > MmsSizeLimits.TOTAL_BUDGET_BYTES) {
            stager.discard(staged)
            errorFlow.value = AttachmentError.TOO_LARGE
            return
        }
        list.value = current + staged
        errorFlow.value = null
    }
}
