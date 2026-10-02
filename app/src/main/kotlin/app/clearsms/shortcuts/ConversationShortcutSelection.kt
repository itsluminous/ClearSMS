package app.clearsms.shortcuts

import app.clearsms.data.db.ShortcutCandidateRow
import app.clearsms.data.repository.SenderNormalizer
import app.clearsms.domain.model.Category

/**
 * The pure half of the launcher-shortcut feature (issue #81): which
 * conversations may appear under a long-press of the app icon, in what
 * order, how many, and under which ids. Nothing here touches Android, so
 * every rule is unit-tested as a plain function; the platform half
 * ([ConversationShortcutPublisher]) only renders and publishes what this
 * object decides.
 *
 * A launcher shortcut is visible to anyone holding the phone, so the
 * EXCLUSION set is a privacy rule, not a cosmetic one. A thread is never
 * published when it is:
 * - a **blocked sender** (the authoritative settings blocklist, matched the
 *   way the blocklist itself matches - and the per-row cache flag, so the
 *   two can never disagree in the launcher);
 * - a **muted sender**. Debatable, decided as exclude: a mute is the user's
 *   explicit demotion of a conversation ("I do not want this surfacing"),
 *   and a shortcut is a promotion to the most prominent surface the device
 *   has. A demoted thread must never be promoted elsewhere; the inbox row
 *   keeps its muted glyph and that stays the one place it shows;
 * - **Spam**-categorised (by its newest live message, the inbox's own
 *   representative);
 * - **binned / deleted**: such a thread has no live representative and so
 *   never reaches the candidate list - the SQL side guarantees it, and
 *   [isExcluded] is also consulted for a pinned shortcut whose thread was
 *   binned after publishing (its row comes back null → excluded).
 * Archived threads are not candidates either: the user tucked them away.
 */
object ConversationShortcutSelection {
    /** Id prefix of every conversation shortcut; the suffix is the app thread id. */
    const val ID_PREFIX = "thread:"

    /**
     * The number of conversation shortcuts that may be published: the
     * system's per-activity budget ([maxPerActivity], from
     * `ShortcutManagerCompat.getMaxShortcutCountPerActivity`) counts static
     * and dynamic shortcuts TOGETHER, so the manifest's static entries
     * ([staticCount], one today: "New message") are subtracted first. Never
     * negative: a system reporting fewer slots than the manifest uses simply
     * gets no conversation shortcuts.
     */
    fun budget(
        maxPerActivity: Int,
        staticCount: Int,
    ): Int = (maxPerActivity - staticCount).coerceAtLeast(0)

    /**
     * How many candidate rows to read for a [budget]: the Kotlin-side
     * exclusions (muted, blocklist) can remove at most one row per entry,
     * so an over-sample of exactly that many rows can never leave the
     * budget unfilled while an eligible thread exists further down.
     */
    fun queryLimit(
        budget: Int,
        blockedCount: Int,
        mutedCount: Int,
    ): Int = budget + blockedCount + mutedCount

    /** The shortcut id for an app thread - stable across restarts, never a provider row id. */
    fun shortcutId(threadId: Long): String = ID_PREFIX + threadId

    /** The thread id encoded in a conversation shortcut id, or null for any other shortcut. */
    fun threadIdOf(shortcutId: String): Long? =
        shortcutId
            .takeIf { it.startsWith(ID_PREFIX) }
            ?.removePrefix(ID_PREFIX)
            ?.toLongOrNull()
            ?.takeIf { it >= 0L }

    /**
     * Whether [row] must NOT be a shortcut - see the class KDoc for the set.
     * A null row (no live message left in the thread) is excluded.
     */
    fun isExcluded(
        row: ShortcutCandidateRow?,
        blockedSenders: Set<String>,
        mutedSenders: Set<String>,
    ): Boolean {
        if (row == null) return true
        if (row.category == Category.SPAM) return true
        if (row.isBlockedSender) return true
        if (SenderNormalizer.matchesAny(blockedSenders, row.sender)) return true
        if (SenderNormalizer.matchesAny(mutedSenders, row.sender)) return true
        return false
    }

    /**
     * The threads to publish, in rank order: pinned threads first (the
     * app's own pinned conversations, newest first among themselves), then
     * the rest newest first - the inbox's order - with the exclusion set
     * applied and the list cut to [budget]. Deterministic for a given input
     * so a republish with unchanged data yields an identical list (and the
     * publisher can skip the rate-limited system call).
     */
    fun select(
        candidates: List<ShortcutCandidateRow>,
        budget: Int,
        blockedSenders: Set<String>,
        mutedSenders: Set<String>,
    ): List<ShortcutCandidateRow> {
        if (budget <= 0) return emptyList()
        return candidates
            .asSequence()
            .filterNot { isExcluded(it, blockedSenders, mutedSenders) }
            .distinctBy { it.threadId }
            .sortedWith(compareByDescending<ShortcutCandidateRow> { it.pinned }.thenByDescending { it.timestamp })
            .take(budget)
            .toList()
    }

    /**
     * The label a shortcut shows: the resolved display name the inbox row
     * shows for the sender (contact name, else directory / brand name, else
     * the raw sender id or number), trimmed, with the raw [sender] as the
     * last resort so a shortcut is never blank. Names and the thread
     * identity only - never a body, OTP, amount or account number, which is
     * why this takes a name and a sender and nothing else.
     */
    fun label(
        resolvedName: String?,
        sender: String,
    ): String = resolvedName?.trim()?.takeIf { it.isNotEmpty() } ?: sender.trim().ifEmpty { "?" }
}
