package app.clearsms.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure contract behind the sent-time sort (GitHub #45): 0/absent is
 * UNKNOWN (never an epoch timestamp), unknown falls back to the received
 * time, RECEIVED ignores the sent time entirely, and the newest-first
 * comparator breaks ties on id exactly like the SQL so the order is stable.
 */
class MessageSortKeyTest {
    private data class Msg(
        val id: Long,
        val received: Long,
        val sent: Long?,
    )

    @Test
    fun `zero, negative and null provider sent times are unknown`() {
        assertThat(sentTimestampOrNull(0L)).isNull()
        assertThat(sentTimestampOrNull(-1L)).isNull()
        assertThat(sentTimestampOrNull(null)).isNull()
        assertThat(sentTimestampOrNull(1_700_000_000_000L)).isEqualTo(1_700_000_000_000L)
    }

    @Test
    fun `received order never looks at the sent time`() {
        assertThat(MessageSortOrder.RECEIVED.sortTimestamp(receivedMs = 5_000L, dateSentMs = 1_000L)).isEqualTo(5_000L)
        assertThat(MessageSortOrder.RECEIVED.sortTimestamp(receivedMs = 5_000L, dateSentMs = null)).isEqualTo(5_000L)
    }

    @Test
    fun `sent order uses the sent time and falls back to received when unknown`() {
        assertThat(MessageSortOrder.SENT.sortTimestamp(receivedMs = 5_000L, dateSentMs = 1_000L)).isEqualTo(1_000L)
        assertThat(MessageSortOrder.SENT.sortTimestamp(receivedMs = 5_000L, dateSentMs = null)).isEqualTo(5_000L)
    }

    @Test
    fun `the reporter's case - arrivals out of order relative to send are fixed only under SENT`() {
        // Sent 10:05 arrived first; sent 10:00 arrived a second later.
        val laterSentArrivedFirst = Msg(id = 1, received = 10_30_00L, sent = 10_05_00L)
        val earlierSentArrivedSecond = Msg(id = 2, received = 10_30_01L, sent = 10_00_00L)
        val noSentTime = Msg(id = 3, received = 9_00_00L, sent = null)
        val messages = listOf(laterSentArrivedFirst, earlierSentArrivedSecond, noSentTime)

        fun newestFirst(order: MessageSortOrder) =
            messages.sortedWith(newestFirstComparator(order, Msg::id, Msg::received, Msg::sent)).map { it.id }

        assertThat(newestFirst(MessageSortOrder.RECEIVED)).isEqualTo(listOf(2L, 1L, 3L))
        assertThat(newestFirst(MessageSortOrder.SENT)).isEqualTo(listOf(1L, 2L, 3L))
    }

    @Test
    fun `ties break on id descending under both orders - the sort is stable and total`() {
        val tied =
            listOf(
                Msg(id = 4, received = 1_000L, sent = 900L),
                Msg(id = 9, received = 1_000L, sent = 900L),
                Msg(id = 6, received = 1_000L, sent = null),
            )
        val received = tied.sortedWith(newestFirstComparator(MessageSortOrder.RECEIVED, Msg::id, Msg::received, Msg::sent))
        assertThat(received.map { it.id }).isEqualTo(listOf(9L, 6L, 4L))
        val sent = tied.sortedWith(newestFirstComparator(MessageSortOrder.SENT, Msg::id, Msg::received, Msg::sent))
        // 6 has no sent time → its received 1000 outranks the 900s.
        assertThat(sent.map { it.id }).isEqualTo(listOf(6L, 9L, 4L))
        // Sorting an already-sorted list reproduces it exactly.
        assertThat(sent.sortedWith(newestFirstComparator(MessageSortOrder.SENT, Msg::id, Msg::received, Msg::sent)))
            .isEqualTo(sent)
    }
}
