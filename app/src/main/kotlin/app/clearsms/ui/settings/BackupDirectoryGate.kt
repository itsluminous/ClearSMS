package app.clearsms.ui.settings

import app.clearsms.ui.common.BackupFrequency

/**
 * Pure decision logic for the "choose a backup directory before the
 * frequency activates" flow, kept out of the ViewModel so the state machine
 * is trivially testable:
 *
 * - Selecting DAILY/WEEKLY with no granted directory does NOT change the
 *   setting — it asks for the directory picker first, remembering the
 *   requested frequency as pending.
 * - A granted pick activates the pending frequency (or just re-points the
 *   directory when no frequency was pending).
 * - A cancelled pick abandons the pending frequency: the setting was never
 *   written, so the row stays OFF; the caller shows an explanatory snackbar.
 * - OFF, and any frequency with a directory already granted, apply directly.
 */
object BackupDirectoryGate {
    /** What selecting a frequency should do. */
    sealed interface FrequencyOutcome {
        /** Persist and (re)schedule [frequency] now. */
        data class Apply(
            val frequency: BackupFrequency,
        ) : FrequencyOutcome

        /** No directory yet: launch the tree picker, holding [pending] until it resolves. */
        data class NeedDirectory(
            val pending: BackupFrequency,
        ) : FrequencyOutcome
    }

    /** What a directory-picker result should do. */
    sealed interface PickOutcome {
        /** Grant persisted; activate [frequency] (the pending request). */
        data class ActivatePending(
            val frequency: BackupFrequency,
        ) : PickOutcome

        /** Grant persisted with no pending frequency: just the location changed. */
        data object LocationUpdated : PickOutcome

        /** Picker cancelled while a frequency was pending: nothing was written, the row stays OFF. */
        data object RevertedToOff : PickOutcome

        /** Picker cancelled with nothing pending (plain location change): no-op. */
        data object Dismissed : PickOutcome
    }

    fun onFrequencySelected(
        requested: BackupFrequency,
        hasDirectory: Boolean,
    ): FrequencyOutcome =
        if (requested == BackupFrequency.OFF || hasDirectory) {
            FrequencyOutcome.Apply(requested)
        } else {
            FrequencyOutcome.NeedDirectory(requested)
        }

    fun onDirectoryPicked(
        granted: Boolean,
        pending: BackupFrequency?,
    ): PickOutcome =
        when {
            granted && pending != null && pending != BackupFrequency.OFF -> PickOutcome.ActivatePending(pending)
            granted -> PickOutcome.LocationUpdated
            pending != null -> PickOutcome.RevertedToOff
            else -> PickOutcome.Dismissed
        }
}

/**
 * Human-readable name of a picked SAF tree for the Settings row summary:
 * `content://…/tree/primary%3ADocuments%2FBackups` reads as "Backups". Falls
 * back to the raw document id when it has no path separator, and to null for
 * an unparseable uri (the row then shows its "not set" placeholder).
 */
fun backupDirectoryDisplayName(treeUriString: String?): String? {
    if (treeUriString.isNullOrBlank()) return null
    val decoded = java.net.URLDecoder.decode(treeUriString.substringAfterLast("/tree/"), Charsets.UTF_8.name())
    val documentId = decoded.substringAfterLast(':')
    val leaf = documentId.trimEnd('/').substringAfterLast('/')
    return leaf.ifBlank { decoded.ifBlank { null } }
}
