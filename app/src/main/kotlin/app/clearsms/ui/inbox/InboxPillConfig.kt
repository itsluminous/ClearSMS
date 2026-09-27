package app.clearsms.ui.inbox

import app.clearsms.domain.model.InboxPill
import app.clearsms.ui.navigation.PillConfig

/**
 * The user's Inbox pill customisation (issue #49), resolved into what the
 * pill row renders: [order] and [hidden] both come from Settings. Pills
 * always show their built-in names (renaming was tried on a branch and
 * dropped before release - the names are already good).
 *
 * Visibility and order resolve through the shared [PillConfig] - the one
 * mechanism the Inbox, Finance and Alerts rows all use, so the three cannot
 * drift. Hiding every pill is legitimate - it yields [visible] empty and
 * the pill row simply disappears, because "no pill selected" is already the
 * app's natural all-messages view and the Unread toggle is an independent
 * control, so nothing is lost. The guard that matters is the other way
 * round: a hidden pill can never be the ACTIVE filter (see
 * [InboxFilterState.constrainedTo]), otherwise a user could sit on a
 * filtered view with no chip left to clear it - e.g. a default filter
 * pointing at a category whose pill they hid.
 *
 * [order] and [hidden] are independent preferences: hiding a pill never
 * rewrites the stored order, so showing it again puts it back in its place.
 */
data class InboxPillConfig(
    val order: List<InboxPill> = emptyList(),
    val hidden: Set<InboxPill> = emptySet(),
) {
    /** Order and visibility, resolved by the shared mechanism. */
    val pills: PillConfig<InboxPill> = PillConfig(InboxPill.entries.toList(), order, hidden)

    /** Every pill in display order, hidden ones included (the Settings view). */
    val ordered: List<InboxPill> get() = pills.ordered

    /** The pills the Inbox row renders, in display order. */
    val visible: List<InboxPill> get() = pills.visible

    /** False when every pill is hidden: the row is not rendered at all. */
    val showsRow: Boolean get() = pills.showsRow
}
