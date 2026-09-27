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
     * The laid-out line count from which the collapsed box shows the expand
     * toggle: one line (or an empty field) has nothing to expand for, so the
     * icon appears only once the text wraps or breaks onto a second line -
     * the Telegram convention the operator asked for.
     */
    const val TOGGLE_MIN_LINES = 2

    /**
     * Whether the expand/shrink toggle is shown, from the REAL laid-out line
     * count (Compose's TextLayoutResult.lineCount for the text as it sits in
     * the field - font scale, emoji, CJK and soft wraps all included; never
     * a character heuristic) and the expansion state. Expanded, the shrink
     * control is unconditional: it is the only visible way back, so it must
     * never depend on how much text there is.
     */
    fun toggleVisible(
        laidOutLineCount: Int,
        expanded: Boolean,
    ): Boolean = expanded || laidOutLineCount >= TOGGLE_MIN_LINES

    /**
     * The line count to hold after a text layout pass reports [reported].
     * A pass may hand back no result at all (the layout is momentarily
     * stale or not yet computed); mapping that to "one line" would blink the
     * icon off between two multi-line layouts, so a missing result KEEPS
     * [current] - the icon only ever moves on a real count. Counts are
     * floored at one line: an empty field still lays out one (empty) line.
     */
    fun nextLaidOutLineCount(
        current: Int,
        reported: Int?,
    ): Int = reported?.coerceAtLeast(1) ?: current

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
        /**
         * The field's height cap in lines (TextFieldLineLimits.MultiLine's
         * maxHeightInLines): text beyond it scrolls inside the field, whose
         * own ScrollState the field follows as the cursor moves. Unbounded
         * expanded - there the fixed-height field is the bound.
         */
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
     * The toggle's state transition - one pure symmetry for BOTH directions,
     * so expand-then-collapse via the icon is the same tested rule, not two
     * ad-hoc writes. Trivial by design: the point is that the composable's
     * single toggle affordance routes through here (pinned by
     * ComposerExpansionConventionTest), which is what makes "the shrink icon
     * does nothing" (issue #30) a testable regression rather than a silent
     * one-liner drift.
     */
    fun toggled(expanded: Boolean): Boolean = !expanded

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
