package app.clearsms.ui.conversation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure contract for opening a conversation AT a highlighted message: the
 * position under the pager's newest-first ordering (timestamp DESC, id
 * DESC - tie-break included) and the Pager initialKey derived from it.
 * The DAO side of the same contract lives in MessagePositionDaoTest.
 */
class ConversationInitialKeyTest {
    private fun key(
        id: Long,
        ts: Long,
    ) = MessageOrderKey(id = id, timestamp = ts)

    @Test
    fun `position counts strictly newer messages`() {
        val keys = listOf(key(1, 100), key(2, 200), key(3, 300))
        // Newest-first order is 3, 2, 1.
        assertThat(newestFirstPositionOf(keys, targetId = 3)).isEqualTo(0)
        assertThat(newestFirstPositionOf(keys, targetId = 2)).isEqualTo(1)
        assertThat(newestFirstPositionOf(keys, targetId = 1)).isEqualTo(2)
    }

    @Test
    fun `timestamp ties break on id exactly like the pager's ORDER BY`() {
        // Bulk imports produce runs of identical timestamps. Order is
        // timestamp DESC, id DESC: 5, 4, 3 (all ts=200), then 1 (ts=100).
        val keys = listOf(key(1, 100), key(3, 200), key(4, 200), key(5, 200))
        assertThat(newestFirstPositionOf(keys, targetId = 5)).isEqualTo(0)
        assertThat(newestFirstPositionOf(keys, targetId = 4)).isEqualTo(1)
        assertThat(newestFirstPositionOf(keys, targetId = 3)).isEqualTo(2)
        assertThat(newestFirstPositionOf(keys, targetId = 1)).isEqualTo(3)
    }

    @Test
    fun `target no longer in the thread resolves to no position`() {
        assertThat(newestFirstPositionOf(listOf(key(1, 100)), targetId = 99)).isNull()
    }

    @Test
    fun `newest message needs no initial key - the default open stays at the top`() {
        // A target that IS on the first page at position 0: initialKey null
        // means the pager starts at the newest message, where it already is.
        assertThat(initialPagingKeyFor(0)).isNull()
    }

    @Test
    fun `any deeper position becomes the initial key so paging starts at its page`() {
        assertThat(initialPagingKeyFor(1)).isEqualTo(1)
        assertThat(initialPagingKeyFor(7_000)).isEqualTo(7_000)
    }

    @Test
    fun `no target means no initial key - the plain conversation open is untouched`() {
        assertThat(initialPagingKeyFor(null)).isNull()
    }
}
