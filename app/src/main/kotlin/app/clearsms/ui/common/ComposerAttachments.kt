package app.clearsms.ui.common

import android.net.Uri
import app.clearsms.mms.MmsSizeBudget
import app.clearsms.mms.OutgoingAttachmentStager
import app.clearsms.mms.StagedAttachment
import app.clearsms.mms.StagingResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Why an attachment could not be added; rendered as the inline error. */
sealed interface AttachmentError {
    /**
     * Adding it would push the message over the sending SIM's carrier MMS
     * limit, even after compression. [limitBytes] is that limit
     * ([MmsSizeBudget.limitBytes]) so the error can name it truthfully.
     */
    data class TooLarge(
        val limitBytes: Long,
    ) : AttachmentError

    /** The content could not be read (revoked grant, vanished document). */
    data object Unreadable : AttachmentError
}

/**
 * Compose-bar attachment state shared by the conversation and
 * new-conversation ViewModels (the same pattern as ConversationDraft):
 * staging, the running size budget, inline errors and removal. NOT
 * persisted: attachment state deliberately does not survive in drafts
 * this wave (text does, as today) - leaving the screen discards the
 * staged files via [discardAll].
 *
 * The budget is the CARRIER's: [subscriptionId] names the SIM the next
 * send will use, and every staging call sizes its attachment to what
 * remains of that SIM's MMS limit ([OutgoingAttachmentStager.budgetFor]).
 * The stager therefore enforces the running total by construction - an
 * image is compressed into the space left, a file that will not fit is
 * refused with the limit named.
 */
class ComposerAttachments(
    private val stager: OutgoingAttachmentStager,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val subscriptionId: () -> Int? = { null },
) {
    private val list = MutableStateFlow<List<StagedAttachment>>(emptyList())
    val attachments: StateFlow<List<StagedAttachment>> = list.asStateFlow()

    /** The latest add failure; cleared by the next successful action. */
    private val errorFlow = MutableStateFlow<AttachmentError?>(null)
    val error: StateFlow<AttachmentError?> = errorFlow.asStateFlow()

    /**
     * The attachment budget the chips are measured against - the carrier
     * limit minus the PDU envelope margin - as last read for the chosen
     * SIM. Refreshed by every staging call and by [refreshBudget].
     */
    private val budgetFlow = MutableStateFlow(MmsSizeBudget.forCarrier(null).attachmentTargetBytes)
    val budgetBytes: StateFlow<Long> = budgetFlow.asStateFlow()

    /** Camera capture in flight, if any (armed by [cameraUri]). */
    private var pendingCapture: File? = null

    /** Stages every [uris] entry (pickers may return several). */
    fun add(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch(dispatcher) {
            uris.forEach { uri ->
                val budget = currentBudget()
                accept(stager.stage(uri, budget, usedBytes = list.value.sumOf { it.sizeBytes }))
            }
        }
    }

    /** Re-reads the chosen SIM's budget (the SIM chooser cycled) so the size line stays truthful. */
    fun refreshBudget() {
        scope.launch(dispatcher) { currentBudget() }
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
                accept(stager.stageCameraResult(target, currentBudget(), usedBytes = list.value.sumOf { it.sizeBytes }))
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

    /** The chosen SIM's budget right now, published for the size line. */
    private fun currentBudget(): MmsSizeBudget {
        val budget = stager.budgetFor(subscriptionId())
        budgetFlow.value = budget.attachmentTargetBytes
        return budget
    }

    /** Staging verdicts become inline errors; a staged attachment already fits the remaining budget. */
    private fun accept(result: StagingResult) {
        when (result) {
            StagingResult.Unreadable -> errorFlow.value = AttachmentError.Unreadable
            is StagingResult.TooLarge -> errorFlow.value = AttachmentError.TooLarge(result.limitBytes)
            is StagingResult.Staged -> {
                list.value = list.value + result.attachment
                errorFlow.value = null
            }
        }
    }
}
