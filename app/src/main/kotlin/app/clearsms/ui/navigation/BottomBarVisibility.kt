package app.clearsms.ui.navigation

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import app.clearsms.domain.model.EnabledSections

/**
 * Pure decision for the shell's bottom navigation bar (issues #39 and its
 * follow-up: the bar arriving a beat too late).
 *
 * History, because both failure modes live at the same seam:
 *  - Originally the bar keyed on the CURRENT back-stack entry alone and was
 *    composed at full opacity the instant a pop started, so it FLASHED over
 *    the still-visible conversation for the whole exit fade (issue #39).
 *  - The first fix gated composition on NavController.visibleEntries, which
 *    keeps the OUTGOING entry listed until its exit transition completes -
 *    so the bar then waited out the entire 700 ms crossfade and the settled
 *    inbox sat bare for a beat: too EARLY became too LATE.
 *
 * The resolution splits the question in two:
 *  1. WHO owns the bar - this object. The bar belongs to the transition's
 *     TARGET destination: [isVisible] is true iff the current (target)
 *     route is a top-level tab and the section toggles leave two or more
 *     tabs to switch between ([EnabledSections.showBottomBar]).
 *  2. WHEN it is on the glass - the animation clock, not a boolean. The
 *     shell wraps the bar in an AnimatedVisibility whose enter runs
 *     fadeIn + expandVertically on [contentTransitionSpec] - the SAME spec
 *     the NavHost's route crossfade uses - so the bar arrives in step with
 *     the incoming tab content: never gated on the transition completing,
 *     and never ahead of it. Because the slot EXPANDS (the scaffold padding
 *     grows with it), the outgoing screen is laid out above the bar at
 *     every frame - the bar cannot draw OVER the conversation by
 *     construction. Exit stays instant (snap): removing chrome early
 *     overlaps nothing, which is the forward behaviour both earlier
 *     versions already had and nobody reported against.
 *
 * No timers, no delays, no tuned durations: the one number below is
 * navigation-compose's own default crossfade, named so the two animations
 * share a single clock (see [contentTransitionSpec]).
 */
object BottomBarVisibility {
    /**
     * navigation-compose's NavHost default enter/exit is
     * `fadeIn/fadeOut(tween(700))` (verified against 2.x bytecode). This is
     * NOT a tuning knob: it names the library default in one place so the
     * NavHost's content crossfade and the bar's arrival provably run on the
     * same spec - change it and both move together, in sync by definition.
     */
    private const val CONTENT_CROSSFADE_MS = 700

    /** The single shared clock: route content and bar animate on this spec. */
    fun <T> contentTransitionSpec(): FiniteAnimationSpec<T> = tween(CONTENT_CROSSFADE_MS)

    /**
     * Does the bar belong on the screen the navigation is HEADING TO?
     * [currentRoute] is the current back-stack entry's route, which flips to
     * the target the moment a navigate/pop starts - exactly the signal that
     * lets the bar's enter animation start WITH the content transition.
     */
    fun isVisible(
        currentRoute: String?,
        sections: EnabledSections,
    ): Boolean = sections.showBottomBar && currentRoute in Routes.topLevel
}
