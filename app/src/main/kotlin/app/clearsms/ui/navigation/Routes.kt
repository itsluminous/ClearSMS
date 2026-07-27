package app.clearsms.ui.navigation

import android.net.Uri

/** Route constants for the single-activity nav graph. */
object Routes {
    const val ONBOARDING = "onboarding"
    const val INBOX = "inbox"
    const val ARCHIVED = "inbox/archived"
    const val FINANCE = "finance"
    const val ALERTS = "alerts"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val PRIVACY_POLICY = "settings/privacy"
    const val LICENSES = "settings/licenses"
    const val PERMISSIONS_INFO = "settings/permissions"
    const val RULES = "rules"

    const val CONVERSATION = "conversation/{threadId}?messageId={messageId}"

    fun conversation(
        threadId: Long,
        messageId: Long = -1L,
    ) = "conversation/$threadId?messageId=$messageId"

    const val COMPOSE = "compose?recipient={recipient}&body={body}"

    fun compose(
        recipient: String? = null,
        body: String? = null,
    ): String = "compose?recipient=${Uri.encode(recipient.orEmpty())}&body=${Uri.encode(body.orEmpty())}"

    const val ACCOUNT_DETAIL = "account/{accountNumber}?bank={bank}"

    fun accountDetail(
        accountNumber: String,
        bank: String,
    ): String = "account/${Uri.encode(accountNumber)}?bank=${Uri.encode(bank)}"

    const val RULE_WIZARD = "ruleWizard?sender={sender}&body={body}"

    fun ruleWizard(
        sender: String? = null,
        body: String? = null,
    ): String = "ruleWizard?sender=${Uri.encode(sender.orEmpty())}&body=${Uri.encode(body.orEmpty())}"

    /** Routes on which the bottom navigation bar is visible. */
    val topLevel = setOf(INBOX, FINANCE, ALERTS)
}
