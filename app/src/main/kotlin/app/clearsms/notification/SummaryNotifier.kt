package app.clearsms.notification

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.clearsms.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Daily / weekly digest notification (REQUIREMENTS §9.5): total debits and
 * credits, OTPs received, and unread important messages.
 */
@Singleton
class SummaryNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun notify(summary: Summary) {
            Channels.ensureCreated(context)
            val text =
                buildSummaryText(
                    summary,
                    moneyLine = context.getString(R.string.summary_money_line),
                    otpLine = context.getString(R.string.summary_otp_line),
                    unreadLine = context.getString(R.string.summary_unread_line),
                )
            val notification =
                NotificationCompat
                    .Builder(context, Channels.SUMMARY)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.summary_title))
                    .setContentText(text.lineSequence().first())
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setAutoCancel(true)
                    .build()
            try {
                NotificationManagerCompat.from(context).notify(SUMMARY_NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {
                // POST_NOTIFICATIONS not granted; onboarding asks for it.
            }
        }

        /** Data going into one digest. */
        data class Summary(
            val totalDebits: Double,
            val totalCredits: Double,
            val otpCount: Int,
            val unreadImportant: Int,
        )

        companion object {
            private const val SUMMARY_NOTIFICATION_ID = 30_001

            /**
             * Pure text assembly, unit-testable without Android. The line
             * templates come from string resources: [moneyLine] takes the
             * debit and credit totals, [otpLine] and [unreadLine] take counts.
             */
            fun buildSummaryText(
                summary: Summary,
                moneyLine: String,
                otpLine: String,
                unreadLine: String,
            ): String =
                listOf(
                    moneyLine.format(summary.totalDebits, summary.totalCredits),
                    otpLine.format(summary.otpCount),
                    unreadLine.format(summary.unreadImportant),
                ).joinToString("\n")
        }
    }
