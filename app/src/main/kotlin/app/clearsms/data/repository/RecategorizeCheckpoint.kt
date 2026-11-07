package app.clearsms.data.repository

/**
 * Durable progress of one full re-sort ([MessageRepository.recategorizeAll]).
 *
 * The re-sort walks the messages table in id order, one small transaction
 * per page. Without a checkpoint a run the system stopped (expedited quota,
 * process death behind a background receive) started over from the first
 * row on the next process start - on a device that kills background work
 * often, the SAME first pages were re-sorted on every incoming message and
 * every live write queued behind them (issue #153). With one, a stopped run
 * resumes at the page after the last committed one.
 *
 * @property afterId id of the last message whose page committed; the next
 *   page starts after it.
 * @property processed messages handled so far, for the progress display.
 * @property touchedAccounts every account id the committed pages derived
 *   into. The orphan-account sweep at the end of a full pass deletes
 *   accounts the pass never touched, so a resumed run must carry the
 *   touches of the pages it is not repeating - otherwise a balance-only
 *   account refreshed in the first half would be swept as an orphan by the
 *   second.
 */
data class RecategorizeCheckpoint(
    val afterId: Long,
    val processed: Int,
    val touchedAccounts: Set<Long>,
)

/**
 * Where [recategorizeAll][MessageRepository.recategorizeAll] keeps its
 * [RecategorizeCheckpoint] between pages.
 *
 * The repository calls [set] after every committed page, [clear] when the
 * pass completes, and reads [get] once at the start to decide where to
 * begin - a null means "from the first row". Deciding whether an EXISTING
 * checkpoint is still valid is the store's job, not the repository's: the
 * [app.clearsms.work.RecategorizeWorker]'s store discards one recorded by a
 * different app version, so a pass started under the previous rules can
 * never finish under the new ones and then record the new version as fully
 * sorted (which would defeat the automatic post-update re-sort that
 * [app.clearsms.work.AutoResortScheduler] fires on any version change).
 */
interface RecategorizeCheckpointStore {
    suspend fun get(): RecategorizeCheckpoint?

    suspend fun set(checkpoint: RecategorizeCheckpoint)

    suspend fun clear()
}
