package app.clearsms.ui.components

/**
 * Pure state logic for the compose box's expand-to-full-screen toggle: a
 * small icon at the box's top right expands the text field to fill all
 * space above the keyboard for writing long messages; the icon becomes a
 * shrink icon that restores the previous size.
 *
 * Every decision lives here (not in the composable) for two reasons:
 * unit-testability without a Compose harness, and PARITY - the standalone
 * compose screen and the in-conversation composer both render
 * [MessageComposerBar], which is the only caller of this object, so the
 * two entry points cannot drift (the same single-implementation pattern as
 * the Finance lists' shared expansion; enforced by
 * ComposerExpansionConventionTest).
 */
object ComposerExpansion {
    /** The collapsed field's growth cap - the pre-feature compose bar value. */
    const val COLLAPSED_MAX_LINES = 4

    /**
     * What the compose bar shows in each expansion state. Expanded hides
     * the attach and Send affordances (the operator's ask) plus the SIM
     * indicator and staged-attachment chips - sending is only reachable
     * collapsed, so send-time affordances return exactly when they are
     * usable; nothing is discarded, chips and errors reappear on collapse.
     * The recipient identity is the one thing that must ALWAYS be visible
     * expanded: the full-screen field covers the top app bar, and the user
     * must never lose sight of who they are writing to.
     */
    data class Affordances(
        val attachVisible: Boolean,
        val attachmentsRowVisible: Boolean,
        val simIndicatorVisible: Boolean,
        val sendVisible: Boolean,
        val recipientHeaderVisible: Boolean,
        /** Expanded, the field fills all height the insets leave it. */
        val fieldFillsHeight: Boolean,
        /** Unbounded expanded - long text scrolls inside the fixed-height field. */
        val fieldMaxLines: Int,
    )

    fun affordances(expanded: Boolean): Affordances =
        if (expanded) {
            Affordances(
                attachVisible = false,
                attachmentsRowVisible = false,
                simIndicatorVisible = false,
                sendVisible = false,
                recipientHeaderVisible = true,
                fieldFillsHeight = true,
                fieldMaxLines = Int.MAX_VALUE,
            )
        } else {
            Affordances(
                attachVisible = true,
                attachmentsRowVisible = true,
                simIndicatorVisible = true,
                sendVisible = true,
                recipientHeaderVisible = false,
                fieldFillsHeight = false,
                fieldMaxLines = COLLAPSED_MAX_LINES,
            )
        }

    /**
     * Whether the system back gesture collapses the box instead of leaving
     * the screen. With Send hidden while expanded, leaving expansion must
     * be obvious and cheap - so back collapses FIRST; once collapsed the
     * handler is disabled and back is never swallowed (it leaves the
     * screen as always).
     */
    fun backCollapsesFirst(expanded: Boolean): Boolean = expanded

    /**
     * The bottom inset the EXPANDED box pads itself by: the larger of the
     * live IME inset and the navigation-bar inset - never an assumed
     * keyboard height. Keyboard open, the IME inset (which subsumes the
     * nav bar) keeps the field wholly above the keyboard; keyboard
     * DISMISSED while expanded, the box deliberately STAYS expanded and
     * the field grows into the freed space down to the nav bar - the user
     * closed the IME to review a long message, and yanking the layout
     * closed would lose their place (collapse stays one tap or one back
     * gesture away).
     */
    fun expandedBottomInsetPx(
        imeBottomPx: Int,
        navigationBarsBottomPx: Int,
    ): Int = maxOf(imeBottomPx, navigationBarsBottomPx)

    /**
     * Whether the compose field should adopt an externally-changed draft
     * value. The field owns a [androidx.compose.ui.text.input.TextFieldValue]
     * (text + selection, saved across config changes); the ViewModel's
     * String draft echoes back through a flow with a frame or two of lag,
     * so blindly adopting any mismatch would clobber in-flight typing and
     * jump the cursor. The only external draft changes in this app cross
     * an EMPTY boundary - a send/schedule consumes the draft to "", a
     * failed send restores the body into the cleared field, the persisted
     * per-thread draft loads into an untouched field - so adoption is
     * gated on one side being empty: a stale non-empty echo racing
     * non-empty typing can never win.
     */
    fun shouldAdoptExternalDraft(
        external: String,
        field: String,
    ): Boolean = external != field && (external.isEmpty() || field.isEmpty())
}
