package app.clearsms.ui.navigation

/**
 * THE pill-visibility mechanism, shared by the Inbox, Finance and Alerts
 * chip rows (issue #49 gave the Inbox this; Finance and Alerts reuse it
 * rather than growing their own). Resolves a screen's stored [order] and
 * [hidden] set against its full pill set [all]:
 *
 * - [ordered] is every pill in display order, hidden ones included - what
 *   the Settings dialogs list;
 * - [visible] is what the chip row renders;
 * - [showsRow] is false when every pill is hidden: the row is not rendered
 *   at all, never left as an empty strip.
 *
 * Visibility floor: NONE, on every screen. The guard that matters is the
 * other way round - a hidden pill can never be the ACTIVE selection, see
 * [activePill] - because a hidden chip has nothing left to unselect it.
 *
 * [order] and [hidden] are independent preferences: hiding a pill never
 * rewrites the stored order, so showing it again puts it back in its place.
 * Both are persisted as enum NAMES and decoded leniently (unknown names
 * dropped, missing pills appended) by the settings reader, so a stale
 * backup or a pill removed in a later version can never crash or hide
 * anything by accident.
 */
data class PillConfig<T>(
    val all: List<T>,
    val order: List<T> = emptyList(),
    val hidden: Set<T> = emptySet(),
) {
    /** Every pill in display order, hidden ones included (the Settings view). */
    val ordered: List<T> = orderedPills(order, all)

    /** The pills the chip row renders, in display order. */
    val visible: List<T> = ordered.filterNot { it in hidden }

    /** False when every pill is hidden: the row is not rendered at all. */
    val showsRow: Boolean get() = visible.isNotEmpty()
}

/**
 * The guard every screen applies to its selection: [selected] stays active
 * only while its chip is [visible]; otherwise the screen falls back to
 * [fallback] - the unfiltered view (`null` for the Inbox,
 * [app.clearsms.ui.alerts.AlertFilter.ALL] for Alerts), or for the Finance tabs the first visible tab (`null`, summary
 * only, when all are hidden). The raw selection is kept by the caller as
 * chosen, so un-hiding the chip restores it.
 */
fun <T> activePill(
    selected: T,
    visible: Collection<T>,
    fallback: T,
): T = if (selected in visible) selected else fallback
