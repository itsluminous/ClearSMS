package app.clearsms.ui.finance

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TransactionPagingTest {
    @Test
    fun `next limit grows by one page`() {
        assertThat(TransactionPaging.nextLimit(TransactionPaging.PAGE_SIZE)).isEqualTo(60)
        assertThat(TransactionPaging.nextLimit(60)).isEqualTo(90)
    }

    @Test
    fun `has more while rows remain, terminal once everything is shown`() {
        assertThat(TransactionPaging.hasMore(shown = 30, total = 95)).isTrue()
        assertThat(TransactionPaging.hasMore(shown = 90, total = 95)).isTrue()
        assertThat(TransactionPaging.hasMore(shown = 95, total = 95)).isFalse()
        assertThat(TransactionPaging.hasMore(shown = 0, total = 0)).isFalse()
    }

    @Test
    fun `loading while a requested page has not been delivered`() {
        // Limit raised to 60 but only 30 rows delivered so far.
        assertThat(TransactionPaging.isLoadingMore(requested = 60, shown = 30, total = 95)).isTrue()
        // Page delivered.
        assertThat(TransactionPaging.isLoadingMore(requested = 60, shown = 60, total = 95)).isFalse()
        // Requested beyond the end: everything shown, nothing to load.
        assertThat(TransactionPaging.isLoadingMore(requested = 120, shown = 95, total = 95)).isFalse()
    }

    @Test
    fun `page satisfied when limit or end of data reached`() {
        assertThat(TransactionPaging.pageSatisfied(requested = 60, shown = 60, total = 95)).isTrue()
        assertThat(TransactionPaging.pageSatisfied(requested = 60, shown = 30, total = 95)).isFalse()
        // Fewer rows than the limit exist — end of data satisfies the page.
        assertThat(TransactionPaging.pageSatisfied(requested = 120, shown = 95, total = 95)).isTrue()
    }
}
