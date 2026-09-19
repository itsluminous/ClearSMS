package app.clearsms.ui.settings

import androidx.annotation.StringRes
import app.clearsms.R
import app.clearsms.domain.model.EnabledSections

/**
 * Settings sections in display order - enum declaration order IS the screen
 * order, so tests can assert the layout without composing anything.
 */
enum class SettingsSection(
    @StringRes val titleRes: Int,
) {
    MESSAGES(R.string.settings_section_messages),
    APPEARANCE(R.string.settings_section_appearance),
    NOTIFICATIONS(R.string.settings_section_notification),
    OTP(R.string.settings_section_otp),
    INBOX(R.string.settings_section_inbox),
    FINANCE(R.string.settings_section_finance),
    ALERTS(R.string.settings_section_alerts),
    STARTUP(R.string.settings_section_startup),
    BACKUP(R.string.settings_section_backup),
    RULES(R.string.settings_section_rules),
    SIGNATURE(R.string.settings_section_signature),
    DONATE(R.string.settings_section_donate),
    ABOUT(R.string.settings_section_about),
}

/**
 * Every settings row in display order - the single source of truth the
 * screen renders (and the search filters). [section] is null for the three
 * standalone entries that trail all sections without a header; they must
 * stay last so the null group renders as one block below the sections.
 */
enum class SettingsItem(
    val section: SettingsSection?,
    @StringRes val titleRes: Int,
) {
    ARCHIVED(SettingsSection.MESSAGES, R.string.settings_archived),
    RECYCLE_BIN(SettingsSection.MESSAGES, R.string.settings_recycle_bin),
    BLOCK_LIST(SettingsSection.MESSAGES, R.string.settings_block_list),
    STRIP_ACCENTS(SettingsSection.MESSAGES, R.string.settings_strip_accents),

    // Delayed sending (GitHub #40): the toggle, then the delay it gates -
    // both beside STRIP_ACCENTS with the other send-behaviour rows.
    DELAYED_SEND(SettingsSection.MESSAGES, R.string.settings_delayed_send),
    DELAYED_SEND_DELAY(SettingsSection.MESSAGES, R.string.settings_delayed_send_delay),
    SHOW_EXTRACTED_DETAILS(SettingsSection.MESSAGES, R.string.settings_show_transaction_details),
    THEME(SettingsSection.APPEARANCE, R.string.settings_theme),
    DYNAMIC_COLOR(SettingsSection.APPEARANCE, R.string.settings_dynamic_color),
    SHOW_RICH_AVATARS(SettingsSection.APPEARANCE, R.string.settings_show_rich_avatars),
    LOGO_BACKGROUND(SettingsSection.APPEARANCE, R.string.settings_logo_background),
    DELIVERY_REPORTS(SettingsSection.NOTIFICATIONS, R.string.settings_delivery_reports),
    NOTIFICATION_ACTIONS(SettingsSection.NOTIFICATIONS, R.string.settings_notification_actions),
    TRANSACTION_NOTIFICATIONS(SettingsSection.NOTIFICATIONS, R.string.settings_transaction_notifications),
    OTP_AUTO_COPY(SettingsSection.OTP, R.string.settings_otp_auto_copy),
    OTP_AUTO_DELETE(SettingsSection.OTP, R.string.settings_otp_auto_delete),
    OTP_SIZE(SettingsSection.OTP, R.string.settings_otp_size),
    CLEAR_OTP(SettingsSection.OTP, R.string.settings_clear_otp),
    SHOW_INBOX_TAB(SettingsSection.INBOX, R.string.settings_show_inbox_tab),
    INBOX_PILL_ORDER(SettingsSection.INBOX, R.string.settings_pill_order),
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
    PERMISSIONS(null, R.string.settings_permissions),
    PRIVACY_POLICY(null, R.string.settings_privacy_policy),
    LICENSES(null, R.string.settings_licenses),
}

/**
 * The rows the settings screen renders given which sections are enabled.
 * A disabled Inbox/Finance/Alerts section keeps ONLY its "Show … tab"
 * toggle - the remaining rows configure a screen that no longer exists, so
 * showing them would be noise (and the toggle staying visible is what lets
 * the user re-enable the section). Every other section is untouched.
 */
fun visibleSettingsItems(sections: EnabledSections): List<SettingsItem> =
    SettingsItem.entries.filter { item ->
        when (item.section) {
            SettingsSection.INBOX -> sections.inbox || item == SettingsItem.SHOW_INBOX_TAB
            SettingsSection.FINANCE -> sections.finance || item == SettingsItem.SHOW_FINANCE_TAB
            SettingsSection.ALERTS -> sections.alerts || item == SettingsItem.SHOW_ALERTS_TAB
            else -> true
        }
    }
