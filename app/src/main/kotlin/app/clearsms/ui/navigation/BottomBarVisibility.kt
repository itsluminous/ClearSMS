package app.clearsms.ui.navigation

import app.clearsms.domain.model.EnabledSections

/**
 * Pure decision for the shell's bottom navigation bar (issue #39).
 *
 * The bar used to key on the CURRENT back-stack entry alone. That signal
 * leads the screen: on BACK from a conversation the entry flips to the tab
 * route the moment the pop starts, so the opaque bar was composed over the
 * conversation for the whole exit transition - the reported flash.
 *
 * The fix adds the NavHost's transition state: [visibleRoutes] is every
 * route the NavHost is still composing (NavController.visibleEntries keeps
 * the OUTGOING entry listed until its exit transition completes). The bar
 * is composed iff every screen on the glass is a bar screen, so it can
 * never overlap a non-tab screen:
 *
 *  - back (conversation -> tab): hidden while the conversation is still
 *    visible; appears exactly when the transition completes, coinciding
 *    with - never leading - the destination it belongs to;
 *  - forward (tab -> conversation): hidden immediately (removing chrome
 *    early cannot overlap anything);
 *  - tab -> tab: both routes are top-level, so the bar never blinks and
 *    the v0.17.2 saveState/restoreState tab contract is untouched;
 *  - no timers or delays: the signal is the transition itself.
 *
 * [EnabledSections.showBottomBar] keeps the section-toggle rule: below two
 * enabled sections there is nothing to switch between, so no bar at all.
 */
object BottomBarVisibility {
    fun isVisible(
        currentRoute: String?,
        visibleRoutes: Collection<String?>,
        sections: EnabledSections,
    ): Boolean =
        sections.showBottomBar &&
            currentRoute in Routes.topLevel &&
            visibleRoutes.all { it in Routes.topLevel }
}
