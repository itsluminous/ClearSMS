package app.clearsms.ui.settings

import androidx.annotation.StringRes
import app.clearsms.R
import app.clearsms.domain.model.EnabledSections

/**
 * Settings sections in display order - enum declaration order IS the order
 * of the top-level Settings screen, so tests can assert the layout without
 * composing anything.
 *
 * Each section is either a SUB-SCREEN ([subScreen] true: the top-level
 * screen shows one entry that opens a screen listing the section's rows) or
 * a DIRECT entry ([subScreen] false: the section holds exactly one row and
 * that row itself sits on the top-level screen - "Default screen" opens its
 * picker there, "Manage rules" goes straight to the rules screen, "SMS
 * signature" opens its editor; a one-row sub-screen would be a pointless
 * extra tap).
 */
enum class SettingsSection(
    @StringRes val titleRes: Int,
    val subScreen: Boolean = true,
) {
    MESSAGES(R.string.settings_section_messages),
    APPEARANCE(R.string.settings_section_appearance),
    NOTIFICATIONS(R.string.settings_section_notification),
    OTP(R.string.settings_section_otp),
    INBOX(R.string.settings_section_inbox),
    FINANCE(R.string.settings_section_finance),
    ALERTS(R.string.settings_section_alerts),
    STARTUP(R.string.settings_section_startup, subScreen = false),
    BACKUP(R.string.settings_section_backup),
    RULES(R.string.settings_section_rules, subScreen = false),
    SIGNATURE(R.string.settings_section_signature, subScreen = false),
    DONATE(R.string.settings_section_donate),
    ABOUT(R.string.settings_section_about),
    ;

    /** The rows this section hosts, in display order. */
    val items: List<SettingsItem>
        get() = SettingsItem.entries.filter { it.section == this }
}

/**
 * Every settings row in display order - the single source of truth the
 * screens render (and the search filters). [section] is never null: every
 * row lives on exactly one screen - the section's sub-screen, or the
 * top-level screen for a direct-entry section - so a row can neither be
 * orphaned nor shown twice (pinned by SettingsCatalogTest).
 */
enum class SettingsItem(
    val section: SettingsSection,
    @StringRes val titleRes: Int,
) {
    ARCHIVED(SettingsSection.MESSAGES, R.string.settings_archived),
    RECYCLE_BIN(SettingsSection.MESSAGES, R.string.settings_recycle_bin),
    BLOCK_LIST(SettingsSection.MESSAGES, R.string.settings_block_list),
    STRIP_ACCENTS(SettingsSection.MESSAGES, R.string.settings_strip_accents),

    // Delayed sending (GitHub #40): the toggle, then the delay it gates -
    // both beside STRIP_ACCENTS with the other send-behaviour rows. The
    // delay row is only rendered while the toggle is on (visibleSettingsItems).
    DELAYED_SEND(SettingsSection.MESSAGES, R.string.settings_delayed_send),
    DELAYED_SEND_DELAY(SettingsSection.MESSAGES, R.string.settings_delayed_send_delay),
    SHOW_EXTRACTED_DETAILS(SettingsSection.MESSAGES, R.string.settings_show_transaction_details),

    // Sort by sent vs received time (GitHub #45): a radio row like the
    // other ordering choices; defaults to received so nothing reshuffles.
    MESSAGE_SORT_ORDER(SettingsSection.MESSAGES, R.string.settings_message_sort_order),
    THEME(SettingsSection.APPEARANCE, R.string.settings_theme),
    DYNAMIC_COLOR(SettingsSection.APPEARANCE, R.string.settings_dynamic_color),
    SHOW_RICH_AVATARS(SettingsSection.APPEARANCE, R.string.settings_show_rich_avatars),
    LOGO_BACKGROUND(SettingsSection.APPEARANCE, R.string.settings_logo_background),
    DELIVERY_REPORTS(SettingsSection.NOTIFICATIONS, R.string.settings_delivery_reports),
    NOTIFICATION_ACTIONS(SettingsSection.NOTIFICATIONS, R.string.settings_notification_actions),
    TRANSACTION_NOTIFICATIONS(SettingsSection.NOTIFICATIONS, R.string.settings_transaction_notifications),

    // Escape hatch to Android's own notification settings for this app -
    // the only place per-channel sound/vibration/importance can be tuned.
    // An ACTION row, not a preference: nothing is stored or backed up.
    SYSTEM_NOTIFICATION_SETTINGS(SettingsSection.NOTIFICATIONS, R.string.settings_system_notifications),
    OTP_AUTO_COPY(SettingsSection.OTP, R.string.settings_otp_auto_copy),
    OTP_AUTO_DELETE(SettingsSection.OTP, R.string.settings_otp_auto_delete),
    OTP_SIZE(SettingsSection.OTP, R.string.settings_otp_size),
    CLEAR_OTP(SettingsSection.OTP, R.string.settings_clear_otp),
    SHOW_INBOX_TAB(SettingsSection.INBOX, R.string.settings_show_inbox_tab),
    INBOX_PILL_ORDER(SettingsSection.INBOX, R.string.settings_pill_order),

    // Pill customisation (issue #49), grouped right under the order they
    // refine: which pills show, what they are called, and the Unread switch.
    INBOX_VISIBLE_PILLS(SettingsSection.INBOX, R.string.settings_inbox_visible_pills),
    INBOX_PILL_LABELS(SettingsSection.INBOX, R.string.settings_inbox_pill_labels),
    INBOX_UNREAD_TOGGLE(SettingsSection.INBOX, R.string.settings_inbox_unread_toggle),
    DEFAULT_INBOX_FILTER(SettingsSection.INBOX, R.string.settings_default_inbox_filter),
    SWIPE_RIGHT(SettingsSection.INBOX, R.string.settings_swipe_right),
    SWIPE_LEFT(SettingsSection.INBOX, R.string.settings_swipe_left),
    SWIPE_DEAD_ZONE(SettingsSection.INBOX, R.string.settings_swipe_dead_zone),
    SORT_AGAIN(SettingsSection.INBOX, R.string.settings_sort_again),
    SHOW_FINANCE_TAB(SettingsSection.FINANCE, R.string.settings_show_finance_tab),
    FINANCE_PILL_ORDER(SettingsSection.FINANCE, R.string.settings_pill_order),
    SHOW_BALANCE(SettingsSection.FINANCE, R.string.settings_show_balance),
    DEFAULT_FINANCE_FILTER(SettingsSection.FINANCE, R.string.settings_default_finance_filter),
    SHOW_ALERTS_TAB(SettingsSection.ALERTS, R.string.settings_show_alerts_tab),
    ALERTS_PILL_ORDER(SettingsSection.ALERTS, R.string.settings_pill_order),
    DEFAULT_SCREEN(SettingsSection.STARTUP, R.string.settings_default_screen),
    BACKUP_NOW(SettingsSection.BACKUP, R.string.settings_backup_now),
    RESTORE(SettingsSection.BACKUP, R.string.settings_restore),
    BACKUP_SETTINGS(SettingsSection.BACKUP, R.string.settings_backup_settings),
    RESTORE_SETTINGS(SettingsSection.BACKUP, R.string.settings_restore_settings),
    BACKUP_FREQUENCY(SettingsSection.BACKUP, R.string.settings_backup_frequency),
    BACKUP_LOCATION(SettingsSection.BACKUP, R.string.settings_backup_location),
    MANAGE_RULES(SettingsSection.RULES, R.string.settings_manage_rules),
    SIGNATURE(SettingsSection.SIGNATURE, R.string.settings_signature),
    UPI(SettingsSection.DONATE, R.string.settings_donate_upi),
    PAYPAL(SettingsSection.DONATE, R.string.settings_donate_paypal),
    VERSION(SettingsSection.ABOUT, R.string.settings_version),
    SOURCE_CODE(SettingsSection.ABOUT, R.string.settings_source_code),

    // Formerly trailing standalone rows; they are about the app, so they
    // live on the About sub-screen below Version and Source code.
    PERMISSIONS(SettingsSection.ABOUT, R.string.settings_permissions),
    PRIVACY_POLICY(SettingsSection.ABOUT, R.string.settings_privacy_policy),
    LICENSES(SettingsSection.ABOUT, R.string.settings_licenses),
    ;

    /**
     * Whether this row is reached through a sub-screen (true) or sits on
     * the top-level Settings screen itself (false) - the fact a deep link
     * or search hit needs to pick its navigation target.
     */
    val nested: Boolean
        get() = section.subScreen
}

/**
 * What the settings screens have to render with, beyond the catalog itself:
 * the two kinds of conditional visibility. Both follow the same pattern -
 * a toggle row stays, the rows it gates disappear while it is off.
 */
data class SettingsVisibility(
    val sections: EnabledSections,
    /** "Delay before sending" - gates the "Sending delay" picker. */
    val delayedSendEnabled: Boolean,
)

/**
 * The rows the settings screens render given the current toggles.
 *
 * A disabled Inbox/Finance/Alerts section keeps ONLY its "Show … tab"
 * toggle - the remaining rows configure a screen that no longer exists, so
 * showing them would be noise (and the toggle staying visible is what lets
 * the user re-enable the section). The section's sub-screen therefore stays
 * listed and reachable, showing just that toggle: hiding the sub-screen
 * would strand a user who disabled the section from inside it.
 *
 * "Sending delay" is meaningless while "Delay before sending" is off, so it
 * is hidden the same way. Every other row is untouched.
 */
fun visibleSettingsItems(visibility: SettingsVisibility): List<SettingsItem> =
    SettingsItem.entries.filter { item ->
        when (item.section) {
            SettingsSection.INBOX -> visibility.sections.inbox || item == SettingsItem.SHOW_INBOX_TAB
            SettingsSection.FINANCE -> visibility.sections.finance || item == SettingsItem.SHOW_FINANCE_TAB
            SettingsSection.ALERTS -> visibility.sections.alerts || item == SettingsItem.SHOW_ALERTS_TAB
            else -> item != SettingsItem.DELAYED_SEND_DELAY || visibility.delayedSendEnabled
        }
    }

/**
 * One entry of the top-level Settings screen, in display order: a section
 * that opens a sub-screen, or the single row of a direct-entry section
 * rendered in place. Derived from [SettingsSection] order, so the top-level
 * layout is a pure function of the catalog.
 */
sealed interface SettingsTopLevelEntry {
    val section: SettingsSection

    /** Opens the section's sub-screen. */
    data class SubScreen(
        override val section: SettingsSection,
    ) : SettingsTopLevelEntry

    /** The section's one row, rendered on the top-level screen itself. */
    data class Direct(
        override val section: SettingsSection,
        val item: SettingsItem,
    ) : SettingsTopLevelEntry
}

/** The top-level Settings screen's entries, in the order they are shown. */
fun settingsTopLevelEntries(): List<SettingsTopLevelEntry> =
    SettingsSection.entries.map { section ->
        if (section.subScreen) {
            SettingsTopLevelEntry.SubScreen(section)
        } else {
            SettingsTopLevelEntry.Direct(section, section.items.single())
        }
    }
