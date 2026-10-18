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
 * No timers, no delays, no tuned offsets: the one number below is the
 * length of the route crossfade, named ONCE so the two animations share a
 * single clock (see [contentTransitionSpec]).
 */
object BottomBarVisibility {
    /**
     * The route crossfade's length, and therefore the bar's (issue #115,
     * "the transition motion effect is too long").
     *
     * Until v0.23 this was 700 ms: navigation-compose's own NavHost default,
     * `fadeIn/fadeOut(tween(700))` (verified against the 2.8.5 bytecode),
     * adopted so the bar and the content provably ran on one spec. The
     * synchronisation argument only ever needed the two to share ONE
     * number, not that number to be the library's - and 700 ms is Material's
     * `motionDurationExtraLong1`, the longest tier, meant for ambient or
     * very large expressive movement, not a tab switch. Every navigation in
     * the app (tab to tab, into and out of a conversation) wore it.
     *
     * 300 ms is `motionDurationMedium2`, inside the 250-400 ms medium band
     * Material gives transitions of this size, and is Compose's own default
     * for exactly this gesture: `AnimationConstants.DefaultDurationMillis`
     * (what `Crossfade` uses) and, within 10 ms, `AnimatedContent`'s default
     * fade-through (90 ms out, 220 ms in). It is still a plain symmetric
     * crossfade, so nothing about the bar's construction above changes.
     *
     * Change it here and ONLY here: the NavHost's enter/exit and the bar's
     * fade/expand all read [contentTransitionSpec], so they move together
     * by definition. The system's animator duration scale (Developer
     * options, or Accessibility > Remove animations, which sets it to 0)
     * scales this too - Compose feeds Settings.Global.animator_duration_scale
     * into every animation - so a user who wants less or none already has a
     * device-wide knob; this constant is the DEFAULT they should not need it
     * for.
     */
    private const val CONTENT_CROSSFADE_MS = 300

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
     * Whether the bar's ENTER should animate this time (issue #63, the
     * cold-start half). The enter animation exists for ONE reason: to keep
     * the arriving bar in step with the crossfade of an incoming tab over
     * an OUTGOING screen. On a cold start there is no outgoing screen - the
     * first frame has no route yet (no bar), the next has the start tab -
     * so a fade/expand there synchronises with nothing and reads as the
     * bar sliding into a screen that was already settled: pure churn. The
     * bar is therefore simply THERE in the frame the route resolves
     * whenever nothing was on the glass before ([outgoingRoute] null:
     * first composition, or an activity recreated straight onto a tab),
     * and animates in every other case - a pop from a conversation
     * (outgoing = the conversation) keeps the #39/#47 behaviour exactly.
     *
     * [outgoingRoute] is the route the shell composed LAST time round; the
     * shell records it after each composition, so at the moment visibility
     * flips it is the route being left.
     */
    fun animatesEnter(outgoingRoute: String?): Boolean = outgoingRoute != null

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
     *    glass for its whole fade-out; the list grows by the bar's height,
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
