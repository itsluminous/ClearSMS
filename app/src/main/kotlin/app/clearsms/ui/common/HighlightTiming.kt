package app.clearsms.ui.common

/**
 * Timing for the "here is the thing you asked for" flash, shared by every
 * screen that arrives pointed at one row - a message opened from search, and
 * a setting a dialog sent the user to. One definition so the gesture feels the
 * same wherever it happens.
 */
object HighlightTiming {
    /** How long the wash stays at full strength before fading. */
    const val HOLD_MS = 1_600L

    /** Fade duration once the hold expires. */
    const val FADE_MS = 600

    /** Strength of the primary-color wash behind the highlighted row. */
    const val WASH_ALPHA = 0.22f
}
