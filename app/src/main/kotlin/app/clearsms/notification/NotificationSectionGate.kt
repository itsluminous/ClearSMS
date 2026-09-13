package app.clearsms.notification

import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.domain.model.StartDestination
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The section gate every notifier consults before posting: a notification
 * belonging to a DISABLED section must never reach the shade (the user
 * switched the whole surface off, and a notification for it would be the
 * app talking about a screen it no longer shows).
 *
 * The mapping, and where each decision is argued:
 *  - INBOX: plain message and promotion notifications, the scam warning,
 *    the OTP notification and the catch-up summary - they all announce an
 *    incoming MESSAGE (OTP and scam are inbox categories; the catch-up
 *    summary is literally "N new messages"), so they follow the Inbox flag.
 *  - FINANCE: the parsed transaction/balance/bill notification
 *    ([TransactionNotifier]) - the notification face of the Finance
 *    dashboard.
 *  - ALERTS: the bill-due reminder ([ReminderNotifier]) - the notification
 *    face of the Alerts screen. Its ALARMS are additionally not scheduled
 *    at all while Alerts is off (see
 *    [app.clearsms.work.ReminderAlarmScheduler]); the notifier's own check
 *    covers the alarm that was already registered before the disable.
 *
 * Deliberately UNGATED (see NotificationSectionConventionTest):
 *  - the send-failure notification: feedback about the user's OWN outgoing
 *    message - suppressing it would silently lose a failed send;
 *  - the initial-sync / re-sort progress notifications: progress of an
 *    operation the user just started, not section content.
 *
 * Gating consults the flags at POST time (a `first()` read, not a cached
 * value), so a notification racing a settings flip errs on the side of the
 * freshest choice. Disabling a section is a view choice - ingestion and
 * parsing continue - so gating here loses no data, only noise.
 */
@Singleton
class NotificationSectionGate
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
    ) {
        /** Whether notifications belonging to [section] may post right now. */
        suspend fun allows(section: StartDestination): Boolean = settingsRepository.enabledSections.first().isEnabled(section)
    }
