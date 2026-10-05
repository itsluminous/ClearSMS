package app.clearsms.ui.settings

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.ui.graphics.vector.ImageVector
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
 *
 * [icon] leads the section's entry on the TOP-LEVEL screen only (the rows
 * inside a sub-screen stay plain). It is catalog data, not a `when` in the
 * screen, so the top-level rows share one render path and a new section
 * cannot compile without choosing its icon. All are Material outlined
 * glyphs already shipped with the app, so no new drawable is added; they
 * are decorative beside the row's label and rendered without a content
 * description (see SettingsScreen), so a screen reader announces the row
 * once. Finance reuses the bottom bar's wallet so the two surfaces agree.
 */
enum class SettingsSection(
    @StringRes val titleRes: Int,
    val icon: ImageVector,
    val subScreen: Boolean = true,
) {
    MESSAGES(R.string.settings_section_messages, Icons.AutoMirrored.Outlined.Chat),
    APPEARANCE(R.string.settings_section_appearance, Icons.Outlined.Palette),
    NOTIFICATIONS(R.string.settings_section_notification, Icons.Outlined.Notifications),

    // The PIN-code glyph (a dotted code box), not a push pin.
    OTP(R.string.settings_section_otp, Icons.Outlined.Pin),
    INBOX(R.string.settings_section_inbox, Icons.Outlined.Inbox),
    FINANCE(R.string.settings_section_finance, Icons.Outlined.AccountBalanceWallet),

    // Alerts are bill reminders and due dates, so a calendar - not a second
    // bell, which would read as a duplicate of Notifications.
    ALERTS(R.string.settings_section_alerts, Icons.Outlined.Event),
    STARTUP(R.string.settings_section_startup, Icons.Outlined.Home, subScreen = false),

    // The restore-arrow glyph rather than a cloud: backups land in a local
    // SAF folder, never in a cloud.
    BACKUP(R.string.settings_section_backup, Icons.Outlined.SettingsBackupRestore),
    RULES(R.string.settings_section_rules, Icons.AutoMirrored.Outlined.Rule, subScreen = false),
    SIGNATURE(R.string.settings_section_signature, Icons.Outlined.Draw, subScreen = false),

    // Shown as "Support": ways to help the project, paid (UPI, PayPal) and
    // free (a GitHub star). The enum name stays DONATE because it IS the
    // sub-screen route segment ("settings/section/DONATE") and the search /
    // highlight target - renaming it would break every existing deep link
    // for a label change.
    DONATE(R.string.settings_section_support, Icons.Outlined.FavoriteBorder),
    ABOUT(R.string.settings_section_about, Icons.Outlined.Info),
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

    // Muted senders: the quiet middle between normal delivery and blocking
    // (messages arrive, nothing notifies). Directly under the block list it
    // is the gentler sibling of.
    MUTED_SENDERS(SettingsSection.MESSAGES, R.string.settings_muted_senders),

    // Launcher shortcuts (GitHub #81): whether pinned and recent
    // conversations appear under a long-press of the app icon. Right after
    // the block and mute lists because those two decide what this surface
    // may ever show - a blocked or muted sender is never a shortcut.
    CONVERSATION_SHORTCUTS(SettingsSection.MESSAGES, R.string.settings_conversation_shortcuts),
    STRIP_ACCENTS(SettingsSection.MESSAGES, R.string.settings_strip_accents),

    // Delayed sending (GitHub #40): the toggle, then the delay it gates -
    // both beside STRIP_ACCENTS with the other send-behaviour rows. The
    // delay row is only rendered while the toggle is on (visibleSettingsItems).
    MMS_SENDING(SettingsSection.MESSAGES, R.string.settings_mms_sending),
    DELAYED_SEND(SettingsSection.MESSAGES, R.string.settings_delayed_send),
    DELAYED_SEND_DELAY(SettingsSection.MESSAGES, R.string.settings_delayed_send_delay),
    SHOW_EXTRACTED_DETAILS(SettingsSection.MESSAGES, R.string.settings_show_transaction_details),

    // Sort by sent vs received time (GitHub #45): a radio row like the
    // other ordering choices; defaults to received so nothing reshuffles.
    MESSAGE_SORT_ORDER(SettingsSection.MESSAGES, R.string.settings_message_sort_order),

    // Order of the conversation selection-bar actions (GitHub #61): which
    // of Copy / Delete / More details / Forward / ... sit inline. In
    // Messages with the other conversation-view rows (extracted details,
    // sort order), right after them: it is about reading messages, not
    // about the Inbox list. Order only - no hiding, every action stays
    // reachable (ConversationSelectionBarLayout).
    SELECTION_ACTION_ORDER(SettingsSection.MESSAGES, R.string.settings_selection_action_order),
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
    // refine: which pills show, and the Unread switch.
    INBOX_VISIBLE_PILLS(SettingsSection.INBOX, R.string.settings_inbox_visible_pills),
    INBOX_UNREAD_TOGGLE(SettingsSection.INBOX, R.string.settings_inbox_unread_toggle),
    DEFAULT_INBOX_FILTER(SettingsSection.INBOX, R.string.settings_default_inbox_filter),
    SWIPE_RIGHT(SettingsSection.INBOX, R.string.settings_swipe_right),
    SWIPE_LEFT(SettingsSection.INBOX, R.string.settings_swipe_left),
    SWIPE_DEAD_ZONE(SettingsSection.INBOX, R.string.settings_swipe_dead_zone),
    SORT_AGAIN(SettingsSection.INBOX, R.string.settings_sort_again),
    SHOW_FINANCE_TAB(SettingsSection.FINANCE, R.string.settings_show_finance_tab),
    FINANCE_PILL_ORDER(SettingsSection.FINANCE, R.string.settings_pill_order),

    // Same pill visibility the Inbox has, right under the order it refines.
    FINANCE_VISIBLE_PILLS(SettingsSection.FINANCE, R.string.settings_inbox_visible_pills),
    SHOW_BALANCE(SettingsSection.FINANCE, R.string.settings_show_balance),
    DEFAULT_FINANCE_FILTER(SettingsSection.FINANCE, R.string.settings_default_finance_filter),

    // Currency override (issue #65): the safety net when detection from the
    // message / SIM is unreliable. Sits last in Finance - a rarely-touched
    // correction, not a daily control.
    FINANCE_CURRENCY(SettingsSection.FINANCE, R.string.settings_finance_currency),
    SHOW_ALERTS_TAB(SettingsSection.ALERTS, R.string.settings_show_alerts_tab),
    ALERTS_PILL_ORDER(SettingsSection.ALERTS, R.string.settings_pill_order),
    ALERTS_VISIBLE_PILLS(SettingsSection.ALERTS, R.string.settings_inbox_visible_pills),
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

    // Rendered only while PAYPAL_DONATION_ENABLED is true (see
    // visibleSettingsItems); the row, its strings and its URL stay so that
    // bringing it back is a one-line flip.
    PAYPAL(SettingsSection.DONATE, R.string.settings_donate_paypal),

    // The no-cost way to help: opens the repository (the same URL as About's
    // Source code row) so the user can star it.
    STAR_ON_GITHUB(SettingsSection.DONATE, R.string.settings_star_on_github),
    VERSION(SettingsSection.ABOUT, R.string.settings_version),
    SOURCE_CODE(SettingsSection.ABOUT, R.string.settings_source_code),

    // In-app diagnostic log report (preview + share); an action row that
    // opens its own screen, nothing stored, nothing to back up.
    SHARE_LOGS(SettingsSection.ABOUT, R.string.settings_share_logs),

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
 * Whether the PayPal row of the Support section is offered. Temporarily
 * OFF: flip this one constant to `true` to bring the row back - nothing
 * else (row, strings, URL, tests) was removed. No reason is shown to users
 * by design; the row simply does not appear while this is false.
 */
internal const val PAYPAL_DONATION_ENABLED = false

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
 * is hidden the same way. The PayPal row follows [paypalEnabled]
 * (production passes [PAYPAL_DONATION_ENABLED]; tests pass both values so
 * neither path can rot). Every other row is untouched.
 */
fun visibleSettingsItems(
    visibility: SettingsVisibility,
    paypalEnabled: Boolean = PAYPAL_DONATION_ENABLED,
): List<SettingsItem> =
    SettingsItem.entries.filter { item ->
        when (item.section) {
            SettingsSection.INBOX -> visibility.sections.inbox || item == SettingsItem.SHOW_INBOX_TAB
            SettingsSection.FINANCE -> visibility.sections.finance || item == SettingsItem.SHOW_FINANCE_TAB
            SettingsSection.ALERTS -> visibility.sections.alerts || item == SettingsItem.SHOW_ALERTS_TAB
            SettingsSection.DONATE -> item != SettingsItem.PAYPAL || paypalEnabled
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

    /** The decorative leading icon of this entry - the section's, by construction. */
    val icon: ImageVector
        get() = section.icon

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
