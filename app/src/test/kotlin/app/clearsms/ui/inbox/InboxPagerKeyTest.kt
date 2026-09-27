package app.clearsms.ui.inbox

import app.cash.turbine.test
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.InboxPill
import app.clearsms.domain.model.MessageSortOrder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The inbox pager is rebuilt from [InboxPagerKey] and nothing else, so the
 * key must be `equals` for two states that differ only presentationally
 * (no rebuild) and differ exactly when the query does (rebuild), and the
 * key flow must swallow repeats - a DataStore-backed settings flow re-emits
 * its unchanged value on every unrelated preference write.
 */
class InboxPagerKeyTest {
    private val important = InboxFilterState(pill = InboxPill.of(Category.IMPORTANT))

    @Test
    fun `equal query inputs give equal keys`() {
        assertThat(InboxPagerKey.of(important, MessageSortOrder.RECEIVED))
            .isEqualTo(InboxPagerKey.of(InboxFilterState(pill = InboxPill.of(Category.IMPORTANT)), MessageSortOrder.RECEIVED))
        assertThat(InboxPagerKey.of(InboxFilterState(), MessageSortOrder.RECEIVED))
            .isEqualTo(InboxPagerKey(category = null, unreadOnly = false, sortOrder = MessageSortOrder.RECEIVED))
    }

    @Test
    fun `key carries only query inputs - nothing presentational can be smuggled in`() {
        // A future field for unread counts, names or banners would break this.
        assertThat(
            InboxPagerKey::class.java.declaredFields
                .filter {
                    !java.lang.reflect.Modifier
                        .isStatic(it.modifiers)
                }.map { it.name },
        ).containsExactly("category", "unreadOnly", "sortOrder")
    }

    @Test
    fun `key changes when the filter or the sort order changes`() {
        val base = InboxPagerKey.of(important, MessageSortOrder.RECEIVED)

        assertThat(InboxPagerKey.of(important.toggleUnread(), MessageSortOrder.RECEIVED)).isNotEqualTo(base)
        assertThat(InboxPagerKey.of(InboxFilterState(), MessageSortOrder.RECEIVED)).isNotEqualTo(base)
        assertThat(InboxPagerKey.of(important, MessageSortOrder.SENT)).isNotEqualTo(base)
    }

    @Test
    fun `repeated emissions of an equal key are suppressed`() =
        runTest {
            val filters = MutableStateFlow(InboxFilterState())
            // Replay-1 shared flow stands in for DataStore, which re-emits the
            // same value whenever ANY preference is written.
            val sortOrders = MutableSharedFlow<MessageSortOrder>(replay = 1).apply { tryEmit(MessageSortOrder.RECEIVED) }

            inboxPagerKeys(filters, sortOrders).test {
                assertThat(awaitItem()).isEqualTo(InboxPagerKey(null, false, MessageSortOrder.RECEIVED))

                sortOrders.emit(MessageSortOrder.RECEIVED)
                sortOrders.emit(MessageSortOrder.RECEIVED)
                filters.value = InboxFilterState() // equal object, distinct instance
                expectNoEvents()

                filters.value = important
                assertThat(awaitItem()).isEqualTo(InboxPagerKey(Category.IMPORTANT, false, MessageSortOrder.RECEIVED))

                sortOrders.emit(MessageSortOrder.SENT)
                assertThat(awaitItem()).isEqualTo(InboxPagerKey(Category.IMPORTANT, false, MessageSortOrder.SENT))
                expectNoEvents()
            }
        }
}
