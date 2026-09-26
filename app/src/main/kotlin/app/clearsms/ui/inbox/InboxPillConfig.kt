package app.clearsms.ui.inbox

import app.clearsms.domain.model.InboxPill
import app.clearsms.ui.navigation.orderedPills

/**
 * The user's Inbox pill customisation (issue #49), resolved into what the
 * pill row renders: [order] and [hidden] come from Settings, [labels] are
 * the display-name overrides.
 *
 * Visibility floor: NONE. Hiding every pill is legitimate - it yields
 * [visible] empty and the pill row simply disappears, because "no pill
 * selected" is already the app's natural all-messages view and the Unread
 * toggle is an independent control, so nothing is lost. The guard that
 * matters is the other way round: a hidden pill can never be the ACTIVE
 * filter (see [InboxFilterState.constrainedTo]), otherwise a user could sit
 * on a filtered view with no chip left to clear it - e.g. a default filter
 * pointing at a category whose pill they hid.
 *
 * [order] and [hidden] are independent preferences: hiding a pill never
 * rewrites the stored order, so showing it again puts it back in its place.
 */
data class InboxPillConfig(
    val order: List<InboxPill> = emptyList(),
    val hidden: Set<InboxPill> = emptySet(),
    val labels: Map<InboxPill, String> = emptyMap(),
) {
    /** Every pill in display order, hidden ones included (the Settings view). */
    val ordered: List<InboxPill> = orderedPills(order, InboxPill.entries.toList())

    /** The pills the Inbox row renders, in display order. */
    val visible: List<InboxPill> = ordered.filterNot { it in hidden }

    /** False when every pill is hidden: the row is not rendered at all. */
    val showsRow: Boolean get() = visible.isNotEmpty()

    /** The label the pill shows: the user's override, else [default]. */
    fun label(
        pill: InboxPill,
        default: (InboxPill) -> String,
    ): String = labels[pill] ?: default(pill)
}
