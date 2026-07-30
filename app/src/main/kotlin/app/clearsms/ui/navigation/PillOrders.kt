package app.clearsms.ui.navigation

/**
 * Resolves a stored pill order against the full pill set [all]:
 *
 * - pills are rendered in the [configured] order;
 * - duplicates are collapsed to their first occurrence;
 * - entries not in [all] (unknown, or values removed in a later version) are
 *   dropped — never a crash;
 * - anything [all] contains that [configured] omits is appended at the end —
 *   never a hidden pill. An empty [configured] therefore yields [all]
 *   (the enum's declaration order) unchanged.
 */
fun <T> orderedPills(
    configured: List<T>,
    all: List<T>,
): List<T> {
    val known = configured.distinct().filter { it in all }
    return known + all.filterNot { it in known }
}
