package app.clearsms.ui.navigation

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

    /**
     * The bottom inset a TOP-LEVEL TAB screen lays out with - the bar's
     * SETTLED height whenever the section toggles produce a bar at all,
     * regardless of what the bar's slot is doing this frame (issue #47).
     *
     * The slot above is what makes the bar arrive with its tab and never
     * overlap a conversation, but it is also an ANIMATED height, and the
     * scaffold's content padding followed it - so a tab's viewport changed
     * during both halves of a conversation round trip:
     *  - forward, the slot snaps to zero while the inbox is still on the
     *    glass for its 700 ms fade-out; the list grows by the bar's height,
     *    a list scrolled to its END fills the new space by scrolling BACK,
     *    and that shifted position is what NavHost saves for the entry;
     *  - back, the slot expands from zero, so the restored list first
     *    measures a full-height viewport and is then squeezed by the bar's
     *    height over the crossfade - and a list never scrolls FORWARD to
     *    keep its end in view, so the last row ends up beneath the bar.
     * A tab's inset therefore must not depend on the slot: it is the bar's
     * resting height (measured from the bar itself, so no Material constant
     * is duplicated) for as long as the sections say there IS a bar, and
     * exactly zero otherwise - a stale measurement can never leave a gap
     * once the bar is gone for good.
     *
     * Non-tab routes keep the animated slot padding: the arriving bar still
     * pushes an outgoing conversation up, so it cannot draw over it.
     */
    fun tabContentInset(
        sections: EnabledSections,
        settledBarHeight: Dp,
    ): Dp = if (sections.showBottomBar) settledBarHeight else 0.dp

    /**
     * What a tab screen adds ON TOP of the scaffold's slot padding
     * ([slotHeight], the bar's animated height this frame) so its total
     * inset is exactly [tabContentInset]: the shortfall while the slot is
     * snapped away (outgoing tab) or still expanding (incoming tab), and
     * zero once the bar is at rest - never a permanent gap, never negative.
     */
    fun tabInsetTopUp(
        sections: EnabledSections,
        settledBarHeight: Dp,
        slotHeight: Dp,
    ): Dp = (tabContentInset(sections, settledBarHeight) - slotHeight).coerceAtLeast(0.dp)
}
