package app.clearsms.ui.navigation

import android.net.Uri

/** Route constants for the single-activity nav graph. */
object Routes {
    const val ONBOARDING = "onboarding"
    const val INBOX = "inbox"
    const val ARCHIVED = "inbox/archived"
    const val RECYCLE_BIN = "inbox/bin"
    const val FINANCE = "finance"
    const val ALERTS = "alerts"
    const val SEARCH = "search"
    const val SETTINGS = "settings?highlight={highlight}"

    /**
     * Settings, optionally scrolled to one row with a brief highlight - the
     * same "here it is" gesture search uses when it opens a message. [item] is
     * a [app.clearsms.ui.settings.SettingsItem] name.
     */
    fun settings(item: String? = null) = "settings?highlight=${Uri.encode(item.orEmpty())}"

    const val PRIVACY_POLICY = "settings/privacy"
    const val LICENSES = "settings/licenses"
    const val PERMISSIONS_INFO = "settings/permissions"
    const val RULES = "rules"

    const val CONVERSATION = "conversation/{threadId}?messageId={messageId}"

    fun conversation(
        threadId: Long,
        messageId: Long = -1L,
    ) = "conversation/$threadId?messageId=$messageId"

    const val COMPOSE = "compose?recipient={recipient}&body={body}&imageUri={imageUri}"

    fun compose(
        recipient: String? = null,
        body: String? = null,
        imageUri: String? = null,
    ): String =
        "compose?recipient=${Uri.encode(recipient.orEmpty())}&body=${Uri.encode(body.orEmpty())}" +
            "&imageUri=${Uri.encode(imageUri.orEmpty())}"

    const val ACCOUNT_DETAIL = "account/{accountNumber}?bank={bank}"

    fun accountDetail(
        accountNumber: String,
        bank: String,
    ): String = "account/${Uri.encode(accountNumber)}?bank=${Uri.encode(bank)}"

    const val RULE_WIZARD = "ruleWizard?sender={sender}&body={body}&ruleId={ruleId}&duplicate={duplicate}"

    fun ruleWizard(
        sender: String? = null,
        body: String? = null,
    ): String = "ruleWizard?sender=${Uri.encode(sender.orEmpty())}&body=${Uri.encode(body.orEmpty())}&ruleId=&duplicate=false"

    /** Opens the rule editor pre-filled with [ruleId]; saving updates the rule in place. */
    fun ruleWizardEdit(ruleId: String): String = "ruleWizard?sender=&body=&ruleId=${Uri.encode(ruleId)}&duplicate=false"

    /** Opens the rule editor on a copy of [ruleId]; saving creates a new user rule. */
    fun ruleWizardDuplicate(ruleId: String): String = "ruleWizard?sender=&body=&ruleId=${Uri.encode(ruleId)}&duplicate=true"

    /** Routes on which the bottom navigation bar is visible. */
    val topLevel = setOf(INBOX, FINANCE, ALERTS)

    /**
     * Routes whose shared [app.clearsms.ui.components.MessageComposerBar]
     * owns the keyboard inset itself (it pads by the live IME/nav-bar union
     * and, sitting in the screen scaffold's bottomBar, pushes the message
     * list up with it). The shell must NOT also apply [androidx.compose.foundation.layout.imePadding]
     * on these routes or the composer gets lifted twice - a keyboard-high
     * band above the compose box, the exact regression the single-inset-owner
     * work just removed. Every other route gets the shell's IME padding.
     */
    val imeSelfManaged = setOf(CONVERSATION, COMPOSE)
}
