package app.clearsms.ui.inbox

import androidx.work.Data
import androidx.work.WorkInfo
import app.clearsms.work.RecategorizeWorker
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * Banner mapping for the post-update re-sort: only an AUTO-tagged run shows
 * the inbox banner (the banner explains a sort the user did NOT ask for;
 * manual sorts keep the settings-row progress alone), it mirrors the
 * worker's progress, and it disappears on completion. Also pins the inbox
 * top-banner precedence: OTP > default-SMS > contacts > sorting.
 */
@RunWith(RobolectricTestRunner::class)
class SortingBannerPolicyTest {
    private fun info(
        state: WorkInfo.State,
        tags: Set<String> = emptySet(),
        processed: Int = 0,
        total: Int = 0,
    ) = WorkInfo(
        id = UUID.randomUUID(),
        state = state,
        tags = tags,
        progress =
            Data
                .Builder()
                .putInt(RecategorizeWorker.PROGRESS_PROCESSED, processed)
                .putInt(RecategorizeWorker.PROGRESS_TOTAL, total)
                .build(),
    )

    @Test
    fun `an AUTO-triggered running sort shows the banner with the worker's progress`() {
        val banner =
            SortingBannerPolicy.select(
                listOf(info(WorkInfo.State.RUNNING, setOf(RecategorizeWorker.TAG_AUTO), processed = 40, total = 100)),
            )
        assertThat(banner).isEqualTo(SortingBanner(processed = 40, total = 100))
    }

    @Test
    fun `an enqueued AUTO run already shows the banner - the sort was not asked for`() {
        val banner =
            SortingBannerPolicy.select(listOf(info(WorkInfo.State.ENQUEUED, setOf(RecategorizeWorker.TAG_AUTO))))
        assertThat(banner).isEqualTo(SortingBanner(processed = 0, total = 0))
    }

    @Test
    fun `a MANUALLY triggered sort never shows the banner`() {
        val banner = SortingBannerPolicy.select(listOf(info(WorkInfo.State.RUNNING, tags = emptySet())))
        assertThat(banner).isNull()
    }

    @Test
    fun `the banner disappears on completion`() {
        val banner =
            SortingBannerPolicy.select(
                listOf(info(WorkInfo.State.SUCCEEDED, setOf(RecategorizeWorker.TAG_AUTO), processed = 100, total = 100)),
            )
        assertThat(banner).isNull()
    }

    @Test
    fun `no work means no banner`() {
        assertThat(SortingBannerPolicy.select(emptyList())).isNull()
    }

    @Test
    fun `inbox banner precedence is pinned - OTP then default-SMS then contacts then sorting`() {
        // The enum's declaration order IS the on-screen top-to-bottom order
        // (the inbox screen iterates entries): the OTP the user opened the
        // app for comes first, the core-functionality default-SMS warning
        // second, the contacts nudge third, and the purely informational
        // sorting progress always yields to anything actionable.
        assertThat(InboxBannerSlot.entries)
            .containsExactly(
                InboxBannerSlot.OTP,
                InboxBannerSlot.DEFAULT_SMS,
                InboxBannerSlot.CONTACTS_PERMISSION,
                InboxBannerSlot.SORTING,
            ).inOrder()
    }
}
