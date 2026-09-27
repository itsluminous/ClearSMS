package app.clearsms.ui.inbox

import app.clearsms.domain.model.Category
import app.clearsms.domain.model.InboxPill
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pill customisation (issue #49) as pure logic: which pills the row shows,
 * the no-minimum floor, how visibility and order stay independent, and the
 * guard that keeps a hidden pill from staying selected. Pills always carry
 * their built-in names: renaming was dropped before it ever shipped.
 */
class InboxPillConfigTest {
    private val all = InboxPill.entries.toList()

    @Test
    fun `defaults show every pill in declaration order`() {
        val config = InboxPillConfig()
        assertThat(config.visible).isEqualTo(all)
        assertThat(config.showsRow).isTrue()
    }

    @Test
    fun `the spam pill is part of the default row`() {
        assertThat(InboxPillConfig().visible).contains(InboxPill.SPAM)
    }

    @Test
    fun `hiding removes exactly the hidden pills and keeps the rest in order`() {
        val config = InboxPillConfig(hidden = setOf(InboxPill.PROMOTIONAL, InboxPill.UNKNOWN))
        assertThat(config.visible)
            .containsExactly(InboxPill.IMPORTANT, InboxPill.PERSONAL, InboxPill.OTP, InboxPill.SPAM)
            .inOrder()
        // The Settings view still lists every pill, hidden ones included.
        assertThat(config.ordered).isEqualTo(all)
    }

    @Test
    fun `every subset of hidden pills resolves to its complement`() {
        // 2^6 combinations, including the reporter's "only Personal and OTP".
        for (mask in 0 until (1 shl all.size)) {
            val hidden = all.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
            val config = InboxPillConfig(hidden = hidden)
            assertThat(config.visible).containsExactlyElementsIn(all - hidden).inOrder()
            assertThat(config.showsRow).isEqualTo(hidden.size < all.size)
        }
        val onlyPersonalAndOtp = InboxPillConfig(hidden = all.toSet() - InboxPill.PERSONAL - InboxPill.OTP)
        assertThat(onlyPersonalAndOtp.visible).containsExactly(InboxPill.PERSONAL, InboxPill.OTP).inOrder()
    }

    @Test
    fun `floor - hiding every pill is allowed and removes the row entirely`() {
        val config = InboxPillConfig(hidden = all.toSet())
        assertThat(config.visible).isEmpty()
        assertThat(config.showsRow).isFalse()
        // Nothing is silently forced back on: "all hidden" is what the user
        // asked for, and the inbox is simply the all-messages view.
    }

    @Test
    fun `pill order is preserved across visibility changes`() {
        val order = listOf(InboxPill.OTP, InboxPill.SPAM, InboxPill.PERSONAL, InboxPill.IMPORTANT)
        val shown = InboxPillConfig(order = order)
        val hiddenScam = shown.copy(hidden = setOf(InboxPill.SPAM))
        val shownAgain = hiddenScam.copy(hidden = emptySet())

        assertThat(hiddenScam.visible)
            .containsExactly(
                InboxPill.OTP,
                InboxPill.PERSONAL,
                InboxPill.IMPORTANT,
                InboxPill.PROMOTIONAL,
                InboxPill.UNKNOWN,
            ).inOrder()
        // Hiding never rewrote the order: un-hiding puts SCAM back in place.
        assertThat(shownAgain.visible).isEqualTo(shown.visible)
        assertThat(shownAgain.order).isEqualTo(order)
    }

    @Test
    fun `stale or partial stored order still yields every pill once`() {
        // A list saved before the spam pill existed, plus a name no pill has.
        val config = InboxPillConfig(order = listOf(InboxPill.OTP, InboxPill.IMPORTANT))
        assertThat(config.ordered.take(2)).containsExactly(InboxPill.OTP, InboxPill.IMPORTANT).inOrder()
        assertThat(config.ordered).containsExactlyElementsIn(all)
        assertThat(config.ordered).containsNoDuplicates()
    }

    @Test
    fun `selecting a pill filters its own category`() {
        assertThat(InboxPill.IMPORTANT.category).isEqualTo(Category.IMPORTANT)
        assertThat(InboxFilterState().selectPill(InboxPill.IMPORTANT).category).isEqualTo(Category.IMPORTANT)
    }

    @Test
    fun `guard - a hidden pill is cleared from the active filter, unread kept`() {
        val visible = InboxPillConfig(hidden = setOf(InboxPill.IMPORTANT)).visible
        val filter = InboxFilterState(pill = InboxPill.IMPORTANT, unreadOnly = true).constrainedTo(visible)
        assertThat(filter.pill).isNull()
        assertThat(filter.unreadOnly).isTrue()
    }

    @Test
    fun `guard - a visible pill and the all view pass through unchanged`() {
        val visible = InboxPillConfig(hidden = setOf(InboxPill.IMPORTANT)).visible
        val personal = InboxFilterState(pill = InboxPill.PERSONAL)
        assertThat(personal.constrainedTo(visible)).isEqualTo(personal)
        assertThat(InboxFilterState().constrainedTo(visible)).isEqualTo(InboxFilterState())
    }

    @Test
    fun `guard - with every pill hidden any selection collapses to all messages`() {
        val none = InboxPillConfig(hidden = all.toSet()).visible
        all.forEach { pill ->
            assertThat(InboxFilterState(pill = pill).constrainedTo(none).pill).isNull()
        }
    }

    @Test
    fun `guard - hiding the unread switch drops an unread-only view, pill kept`() {
        val visible = InboxPillConfig().visible
        val filter = InboxFilterState(pill = InboxPill.OTP, unreadOnly = true)
        val hiddenSwitch = filter.constrainedTo(visible, unreadControl = false)
        assertThat(hiddenSwitch.unreadOnly).isFalse()
        assertThat(hiddenSwitch.pill).isEqualTo(InboxPill.OTP)
        // With the switch shown the flag is untouched.
        assertThat(filter.constrainedTo(visible, unreadControl = true)).isEqualTo(filter)
    }

    @Test
    fun `every category has exactly one pill, the spam pill included`() {
        Category.entries.forEach { category ->
            assertThat(InboxPill.of(category).category).isEqualTo(category)
            assertThat(all.count { it.category == category }).isEqualTo(1)
        }
        assertThat(InboxPill.SPAM.category).isEqualTo(Category.SPAM)
        assertThat(InboxPill.of(Category.SPAM)).isEqualTo(InboxPill.SPAM)
    }
}
