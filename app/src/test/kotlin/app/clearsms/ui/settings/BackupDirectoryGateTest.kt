package app.clearsms.ui.settings

import app.clearsms.ui.common.BackupFrequency
import app.clearsms.ui.settings.BackupDirectoryGate.FrequencyOutcome
import app.clearsms.ui.settings.BackupDirectoryGate.PickOutcome
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The directory-first-time state machine: DAILY/WEEKLY never activate before
 * a backup directory is granted, a cancelled picker leaves the row at OFF,
 * and a granted pick activates exactly the frequency that was pending.
 */
class BackupDirectoryGateTest {
    @Test
    fun `selecting DAILY with no directory asks for the picker instead of applying`() {
        val outcome = BackupDirectoryGate.onFrequencySelected(BackupFrequency.DAILY, hasDirectory = false)
        assertThat(outcome).isEqualTo(FrequencyOutcome.NeedDirectory(BackupFrequency.DAILY))
    }

    @Test
    fun `selecting WEEKLY with a directory already granted applies directly`() {
        val outcome = BackupDirectoryGate.onFrequencySelected(BackupFrequency.WEEKLY, hasDirectory = true)
        assertThat(outcome).isEqualTo(FrequencyOutcome.Apply(BackupFrequency.WEEKLY))
    }

    @Test
    fun `selecting OFF never needs a directory`() {
        val outcome = BackupDirectoryGate.onFrequencySelected(BackupFrequency.OFF, hasDirectory = false)
        assertThat(outcome).isEqualTo(FrequencyOutcome.Apply(BackupFrequency.OFF))
    }

    @Test
    fun `granting the directory activates the pending frequency`() {
        val outcome = BackupDirectoryGate.onDirectoryPicked(granted = true, pending = BackupFrequency.WEEKLY)
        assertThat(outcome).isEqualTo(PickOutcome.ActivatePending(BackupFrequency.WEEKLY))
    }

    @Test
    fun `cancelling the picker while a frequency is pending reverts to OFF`() {
        val outcome = BackupDirectoryGate.onDirectoryPicked(granted = false, pending = BackupFrequency.DAILY)
        assertThat(outcome).isEqualTo(PickOutcome.RevertedToOff)
    }

    @Test
    fun `granting with nothing pending is a plain location change`() {
        val outcome = BackupDirectoryGate.onDirectoryPicked(granted = true, pending = null)
        assertThat(outcome).isEqualTo(PickOutcome.LocationUpdated)
    }

    @Test
    fun `cancelling a plain location change is a no-op, not an OFF snackbar`() {
        val outcome = BackupDirectoryGate.onDirectoryPicked(granted = false, pending = null)
        assertThat(outcome).isEqualTo(PickOutcome.Dismissed)
    }

    @Test
    fun `directory display name is derived from the tree uri`() {
        assertThat(
            backupDirectoryDisplayName(
                "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FBackups",
            ),
        ).isEqualTo("Backups")
        assertThat(
            backupDirectoryDisplayName("content://com.android.externalstorage.documents/tree/primary%3ABackups"),
        ).isEqualTo("Backups")
        assertThat(backupDirectoryDisplayName(null)).isNull()
        assertThat(backupDirectoryDisplayName("")).isNull()
    }
}
