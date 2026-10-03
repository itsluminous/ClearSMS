package app.clearsms.shortcuts

import app.clearsms.data.db.ShortcutCandidateRow
import app.clearsms.domain.model.Category
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The launcher-shortcut rules as pure functions (issue #81): the budget
 * arithmetic, pinned-then-recent ordering, the exclusion set (blocked,
 * muted, Spam, binned), label choice and id stability. No Android here -
 * the publisher only renders what these decide.
 */
class ConversationShortcutSelectionTest {
    private fun row(
        threadId: Long,
        sender: String = "+91 98765 4${threadId.toString().padStart(4, '0')}",
        timestamp: Long = threadId * 1_000L,
        pinnedAt: Long? = null,
        category: Category = Category.PERSONAL,
        blockedFlag: Boolean = false,
    ) = ShortcutCandidateRow(
        threadId = threadId,
        sender = sender,
        normalizedSender = sender.filter(Char::isDigit),
        timestamp = timestamp,
        category = category,
        isBlockedSender = blockedFlag,
        pinnedAt = pinnedAt,
    )

    private fun select(
        candidates: List<ShortcutCandidateRow>,
        budget: Int = 4,
        blocked: Set<String> = emptySet(),
        muted: Set<String> = emptySet(),
    ) = ConversationShortcutSelection.select(candidates, budget, blocked, muted).map { it.threadId }

    // region budget

    @Test
    fun `budget is the system maximum minus the static shortcuts, never negative`() {
        // The Samsung shape: a 5-slot system with one static "New message"
        // leaves room for exactly the budget of conversations the launcher
        // then trims to what it shows.
        assertThat(ConversationShortcutSelection.budget(maxPerActivity = 5, staticCount = 1)).isEqualTo(4)
        assertThat(ConversationShortcutSelection.budget(maxPerActivity = 15, staticCount = 1)).isEqualTo(14)
        assertThat(ConversationShortcutSelection.budget(maxPerActivity = 1, staticCount = 1)).isEqualTo(0)
        assertThat(ConversationShortcutSelection.budget(maxPerActivity = 0, staticCount = 1)).isEqualTo(0)
    }

    @Test
    fun `query limit over-samples by exactly the Kotlin-side exclusion sets`() {
        assertThat(ConversationShortcutSelection.queryLimit(budget = 4, blockedCount = 2, mutedCount = 3)).isEqualTo(9)
        assertThat(ConversationShortcutSelection.queryLimit(budget = 0, blockedCount = 0, mutedCount = 0)).isEqualTo(0)
    }

    @Test
    fun `a zero budget publishes nothing even with eligible threads`() {
        assertThat(select(listOf(row(1), row(2)), budget = 0)).isEmpty()
    }

    @Test
    fun `the list is cut to the budget after ordering`() {
        val candidates = (1L..10L).map { row(it) }
        assertThat(select(candidates, budget = 3)).containsExactly(10L, 9L, 8L).inOrder()
    }

    // endregion

    // region ordering

    @Test
    fun `pinned threads come first, newest first within each group`() {
        val candidates =
            listOf(
                row(1, timestamp = 100, pinnedAt = 5),
                row(2, timestamp = 900),
                row(3, timestamp = 300, pinnedAt = 1),
                row(4, timestamp = 500),
            )
        assertThat(select(candidates)).containsExactly(3L, 1L, 2L, 4L).inOrder()
    }

    @Test
    fun `a pinned thread survives the cut over a newer unpinned one`() {
        val candidates = listOf(row(1, timestamp = 1, pinnedAt = 1), row(2, timestamp = 50), row(3, timestamp = 40))
        assertThat(select(candidates, budget = 2)).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `input order does not matter and duplicates collapse to one shortcut per thread`() {
        val shuffled = listOf(row(2, timestamp = 20), row(3, timestamp = 30), row(2, timestamp = 20), row(1, timestamp = 10))
        assertThat(select(shuffled)).containsExactly(3L, 2L, 1L).inOrder()
        assertThat(select(shuffled.reversed())).containsExactly(3L, 2L, 1L).inOrder()
    }

    // endregion

    // region exclusions

    @Test
    fun `a Spam thread is never a shortcut, pinned or not`() {
        val spam = row(1, category = Category.SPAM, pinnedAt = 1)
        assertThat(select(listOf(spam, row(2)))).containsExactly(2L)
        assertThat(ConversationShortcutSelection.isExcluded(spam, emptySet(), emptySet())).isTrue()
    }

    @Test
    fun `a blocked sender is excluded by the per-row flag and by the settings blocklist alike`() {
        val flagged = row(1, blockedFlag = true)
        val listed = row(2, sender = "+91 98765 43210")
        val fine = row(3, sender = "+91 98765 00000")
        assertThat(select(listOf(flagged, listed, fine), blocked = setOf("9876543210"))).containsExactly(3L)
    }

    @Test
    fun `the blocklist matches the way the blocklist itself matches - dialling variants of one number`() {
        val listed = row(1, sender = "+91 98765 43210")
        // Stored with the country code but no plus, as a legacy entry would be.
        assertThat(select(listOf(listed), blocked = setOf("919876543210"))).isEmpty()
        // An alphanumeric id blocked by its core name catches the routed variant.
        val brand = row(2, sender = "VM-JIOPAY")
        assertThat(select(listOf(brand), blocked = setOf("JIOPAY"))).isEmpty()
    }

    @Test
    fun `a muted sender is excluded - a demoted thread is never promoted to the launcher`() {
        val muted = row(1, sender = "+91 98765 43210", pinnedAt = 1)
        val fine = row(2, sender = "+91 98765 00000")
        assertThat(select(listOf(muted, fine), muted = setOf("9876543210"))).containsExactly(2L)
    }

    @Test
    fun `a thread with no live message left is excluded - the pinned-shortcut re-check`() {
        // MessageDao.shortcutCandidateForThread returns null once every row
        // of the thread is binned or deleted.
        assertThat(ConversationShortcutSelection.isExcluded(null, emptySet(), emptySet())).isTrue()
        assertThat(ConversationShortcutSelection.isExcluded(row(1), emptySet(), emptySet())).isFalse()
    }

    @Test
    fun `every other category is eligible`() {
        for (category in Category.entries.filterNot { it == Category.SPAM }) {
            assertThat(ConversationShortcutSelection.isExcluded(row(1, category = category), emptySet(), emptySet()))
                .isFalse()
        }
    }

    // endregion

    // region identity and label

    @Test
    fun `shortcut ids carry the app thread id and round-trip`() {
        assertThat(ConversationShortcutSelection.shortcutId(42L)).isEqualTo("thread:42")
        assertThat(ConversationShortcutSelection.threadIdOf("thread:42")).isEqualTo(42L)
        assertThat(ConversationShortcutSelection.threadIdOf("compose")).isNull()
        assertThat(ConversationShortcutSelection.threadIdOf("thread:")).isNull()
        assertThat(ConversationShortcutSelection.threadIdOf("thread:-1")).isNull()
        assertThat(ConversationShortcutSelection.threadIdOf("thread:abc")).isNull()
    }

    @Test
    fun `shortcut id is a pure function of the thread id - stable across restarts`() {
        // The same thread yields the same id on every run; no counter, no
        // provider row id, nothing process-local.
        assertThat(ConversationShortcutSelection.shortcutId(7L)).isEqualTo(ConversationShortcutSelection.shortcutId(7L))
        assertThat(ConversationShortcutSelection.shortcutId(7L)).isNotEqualTo(ConversationShortcutSelection.shortcutId(8L))
    }

    @Test
    fun `label is the resolved display name, else the raw sender, never blank`() {
        assertThat(ConversationShortcutSelection.label("  Priya  ", "+919876543210")).isEqualTo("Priya")
        assertThat(ConversationShortcutSelection.label(null, "VM-HDFCBK")).isEqualTo("VM-HDFCBK")
        assertThat(ConversationShortcutSelection.label("   ", " +919876543210 ")).isEqualTo("+919876543210")
        assertThat(ConversationShortcutSelection.label(null, "  ")).isEqualTo("?")
    }

    // endregion
}
