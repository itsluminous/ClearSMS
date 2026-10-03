package app.clearsms.ui.inbox

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Where the inbox's compose action lives for the current window. */
enum class FabPlacement {
    /** The Material FAB, floating over the list's bottom-end corner. */
    FLOATING,

    /**
     * An icon action in the app bar (first, before Search and Settings):
     * the compose affordance stays one tap away but no longer floats over
     * list content it would obscure.
     */
    APP_BAR,
}

/**
 * Pure rule: a floating button must not cover a list control at rest.
 *
 * The FAB floats over the Scaffold content's bottom-end corner and covers
 * a [FabFootprint]-square there: the 56dp button plus the 16dp margin the
 * Scaffold places it at. Whatever the list shows in that corner is
 * obscured - and the inbox's top banners (OTP, default-SMS, contacts; see
 * [InboxBannerSlot]) all keep their controls at their TRAILING edge, the X
 * and Copy of the OTP banner among them. At rest the first banner starts
 * at the top of the content area, so its controls land under the FAB
 * exactly when the content area is too short to hold the banner AND the
 * footprint one above the other.
 *
 * Measured, at rest (list at the top, LargeTopAppBar expanded, three
 * sections so the bottom bar shows):
 *
 *  - chrome: status bar 24 + LargeTopAppBar 152 + NavigationBar 80 = 256dp,
 *    plus up to 24dp of gesture-navigation inset under the bar = 280dp
 *    ([RestChrome]); so a phone's landscape window of 360-432dp leaves a
 *    content area of 80-176dp, of which the FAB owns the bottom 72dp;
 *  - the OTP banner, the tallest single banner: 8dp list gutter + 12dp
 *    card padding + label (12sp on a 16sp line) + code (32sp on
 *    displaySmall's 44sp line) + 12dp = 92dp at font scale 1.0, and
 *    152dp at scale 2.0 with linear sp scaling ([TallestBannerAtRest];
 *    Android 14's non-linear scaling lands nearer 110dp). Its 48dp X
 *    button is centred on the card, trailing, directly above the FAB in
 *    the horizontal (both end 16dp + 8dp in from the edge, X 48dp wide,
 *    FAB 56dp - the X lies entirely within the FAB's columns).
 *
 * So on a 411dp-tall landscape window (3-button navigation, content 155dp,
 * FAB at 83-139dp): at scale 1.0 the X spans 26-74dp and clears the FAB by
 * 9dp; at scale 2.0 it spans 56-104dp and the FAB covers its lower 21dp -
 * the defect. With gesture navigation (content 131dp, FAB at 59-115dp) the
 * X is covered at scale 1.0 as well, and the Copy button, whose trailing
 * edge meets the FAB's leading edge, has no clearance at any scale.
 *
 * The honest condition is therefore the WINDOW HEIGHT, not the
 * orientation: a window shorter than [ShortWindowLimit] cannot hold the
 * chrome, the tallest banner and the FAB footprint, so the compose action
 * moves into the app bar ([FabPlacement.APP_BAR]) and nothing floats over
 * the list. A tablet in landscape (600dp+) is not short and keeps its FAB;
 * a phone in portrait split-screen (a ~400dp window) IS short and gets the
 * app-bar action, as it should - "landscape" would have been wrong both
 * ways. Multi-window is handled for free: the window height is the app's
 * own window, not the display.
 *
 * In the floating case the list also carries [listBottomPadding] - the
 * standard Material clearance - so EVERY row, the last conversation and a
 * second or third stacked banner included, can be scrolled fully clear of
 * the FAB: the padding makes any item ending inside the footprint
 * scrollable by at least the footprint. At rest on a window that is not
 * short, a single banner clears the FAB by construction (that is what the
 * limit guarantees).
 *
 * Why relocate rather than hide: the FAB already yields while a snackbar
 * is up, because composing is never urgent while an undo is on offer. But
 * a short window is the device's steady state, and composing is the
 * inbox's primary action - it must stay one tap away, so it moves instead
 * of vanishing.
 */
object InboxFabPlacement {
    /** The FAB (56dp) plus the Scaffold's 16dp margin: the square it covers at the content's bottom-end. */
    val FabFootprint: Dp = 72.dp

    /**
     * Status bar (24) + LargeTopAppBar expanded (152) + NavigationBar (80)
     * + gesture-navigation bottom inset (24): the most the fixed chrome
     * takes from the window at rest.
     */
    val RestChrome: Dp = 280.dp

    /**
     * The OTP banner at font scale 2.0 with linear sp scaling, list gutter
     * included: 8 + 12 + 32 + 88 + 12 = 152dp - the tallest single banner
     * the inbox stacks at the top of the list.
     */
    val TallestBannerAtRest: Dp = 152.dp

    /**
     * A window shorter than this cannot show the chrome, the tallest banner
     * and the FAB footprint one above the other: 280 + 152 + 72 = 504dp.
     * Every phone in landscape (360-432dp) is below it; every tablet, and a
     * phone in portrait (640dp+), is above it. It sits just above Material's
     * 480dp compact-height boundary for the same reason that boundary
     * exists.
     */
    val ShortWindowLimit: Dp = RestChrome + TallestBannerAtRest + FabFootprint

    /**
     * Bottom contentPadding of the list while the FAB floats: the footprint
     * plus one 16dp gutter (Material's customary 88dp), so a row that ends
     * under the FAB can always be scrolled above it.
     */
    val ListClearance: Dp = 88.dp

    /** The compose action's home for a window of [windowHeight]. */
    fun placement(windowHeight: Dp): FabPlacement = if (windowHeight < ShortWindowLimit) FabPlacement.APP_BAR else FabPlacement.FLOATING

    /** The list's bottom clearance: [ListClearance] under a floating FAB, nothing when nothing floats. */
    fun listBottomPadding(placement: FabPlacement): Dp =
        when (placement) {
            FabPlacement.FLOATING -> ListClearance
            FabPlacement.APP_BAR -> 0.dp
        }

    /**
     * Whether a control that ends [controlBottom] below the top of a
     * content area [contentHeight] tall lies in the FAB's footprint - the
     * geometric fact the rule above is built on, kept pure so the measured
     * cases can be asserted.
     */
    fun inFootprint(
        contentHeight: Dp,
        controlTop: Dp,
        controlBottom: Dp,
    ): Boolean = controlBottom > contentHeight - FabFootprint && controlTop < contentHeight
}
