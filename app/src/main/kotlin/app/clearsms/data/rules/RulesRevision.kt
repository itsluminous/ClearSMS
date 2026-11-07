package app.clearsms.data.rules

import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide "the rules table changed" signal: a monotonic counter every
 * writer of the `rules` table bumps right after its write commits, and the
 * classifier's decoded rule snapshot is keyed on.
 *
 * Why it exists: decoding the enabled rule set (466 bundled rows plus the
 * user's) costs tens of milliseconds, and the receive path used to pay it
 * on EVERY message. The snapshot is now cached, which makes invalidation
 * the whole correctness question - a rule the user just saved MUST decide
 * the very next message. Two layers guarantee that:
 *
 * 1. **Explicit bumps**, synchronous with the write, from every known
 *    writer: [app.clearsms.data.repository.RuleRepositoryImpl] (add /
 *    delete / enable / import / restore of a parked rule),
 *    [BundledRuleLoader.reseed] (bundled document version or fingerprint
 *    changed), and the two backup restores
 *    ([app.clearsms.data.backup.RoomUserRuleStore],
 *    [app.clearsms.data.backup.BackupManager]). The bump happens in the
 *    writer's own coroutine after the row write returns, so by the time
 *    the UI reports "saved" the cache is already stale.
 * 2. **Room's invalidation tracker** on the `rules` table, registered by
 *    the snapshot owner, bumps it too - the safety net for any writer the
 *    list above does not know about. That path is asynchronous (it runs
 *    after Room notices the commit), which is why it is the net and not
 *    the mechanism.
 *
 * The counter is per process on purpose: a fresh process has no cache to
 * invalidate, and the bundled reseed that an app update triggers bumps it
 * before the first message of the new version is classified.
 */
class RulesRevision {
    private val counter = AtomicLong(0L)

    /** The current revision; compare, never interpret. */
    val current: Long get() = counter.get()

    /** Marks the rules table as changed. Cheap and thread-safe; call after every write. */
    fun bump() {
        counter.incrementAndGet()
    }
}
