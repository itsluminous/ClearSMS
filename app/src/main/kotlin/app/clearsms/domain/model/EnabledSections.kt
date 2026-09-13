package app.clearsms.domain.model

/**
 * Which of the three top-level sections (the bottom-navigation tabs) the
 * user has enabled. Purely a VIEW choice: disabling a section hides its tab
 * and its settings, but ingestion, categorization, transaction extraction
 * and reminder parsing all keep running, so re-enabling shows complete
 * history rather than a gap.
 *
 * Construct from stored flags via [from], which normalizes the impossible
 * all-off combination - [visibleTabs] is therefore never empty and
 * [resolveStart]'s `first()` can never throw.
 */
data class EnabledSections(
    val inbox: Boolean = true,
    val finance: Boolean = true,
    val alerts: Boolean = true,
) {
    fun isEnabled(tab: StartDestination): Boolean =
        when (tab) {
            StartDestination.INBOX -> inbox
            StartDestination.FINANCE -> finance
            StartDestination.ALERTS -> alerts
        }

    /** The tabs the bottom bar shows, in canonical declaration order. */
    val visibleTabs: List<StartDestination>
        get() = StartDestination.entries.filter(::isEnabled)

    /**
     * Whether the bottom navigation bar is shown at all. A single-item bar
     * offers no navigation, only dead chrome, so the bar disappears below
     * two enabled sections - the lone remaining screen still reaches Search
     * and Settings through its own top bar.
     */
    val showBottomBar: Boolean
        get() = visibleTabs.size >= 2

    /**
     * The last-section guard: [tab] may be switched off only while another
     * section remains enabled - an app with no screens is not a valid state.
     * Callers refuse the toggle and explain why instead of leaving a
     * silently dead switch.
     */
    fun canDisable(tab: StartDestination): Boolean = !(isEnabled(tab) && visibleTabs.size == 1)

    /**
     * The tab the app opens on (cold start included): the user's preferred
     * start destination when its section is enabled, otherwise the first
     * enabled tab in canonical order. The stored preference is never
     * rewritten - re-enabling the section restores it.
     */
    fun resolveStart(preferred: StartDestination): StartDestination = if (isEnabled(preferred)) preferred else visibleTabs.first()

    companion object {
        /**
         * Builds from raw stored flags, normalizing all-off to all-on. The
         * settings UI can never produce all-off (see [canDisable]), but a
         * hand-edited settings backup can restore it - reads heal it here.
         */
        fun from(
            inbox: Boolean,
            finance: Boolean,
            alerts: Boolean,
        ): EnabledSections =
            if (!inbox && !finance && !alerts) {
                EnabledSections()
            } else {
                EnabledSections(inbox = inbox, finance = finance, alerts = alerts)
            }
    }
}
