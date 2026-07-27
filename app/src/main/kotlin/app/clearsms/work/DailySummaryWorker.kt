package app.clearsms.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.clearsms.data.db.MessageDao
import app.clearsms.data.db.TransactionDao
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SummaryFrequency
import app.clearsms.domain.model.TransactionType
import app.clearsms.notification.SummaryNotifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * Builds and posts the digest notification (REQUIREMENTS §9.5): today's total
 * debits and credits, OTPs received, and unread important messages.
 *
 * Runs every 24 hours; the user's frequency setting is honored at run time —
 * OFF skips entirely and WEEKLY only fires on Mondays.
 */
@HiltWorker
class DailySummaryWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val transactionDao: TransactionDao,
        private val messageDao: MessageDao,
        private val settingsRepository: SettingsRepository,
        private val summaryNotifier: SummaryNotifier,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            val frequency = settingsRepository.summaryFrequency.first()
            if (!shouldRun(frequency, LocalDate.now().dayOfWeek)) return Result.success()

            val startOfDay =
                LocalDate
                    .now()
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            val todaysTransactions = transactionDao.getAll().filter { it.timestamp >= startOfDay }
            val otpCount = messageDao.getAll().count { it.category == Category.OTP && it.timestamp >= startOfDay }
            val unreadImportant =
                messageDao
                    .observeUnreadCounts()
                    .first()
                    .firstOrNull { it.category == Category.IMPORTANT }
                    ?.count ?: 0

            summaryNotifier.notify(
                SummaryNotifier.Summary(
                    totalDebits =
                        todaysTransactions.filter { it.type == TransactionType.DEBIT }.sumOf { it.amount },
                    totalCredits =
                        todaysTransactions.filter { it.type == TransactionType.CREDIT }.sumOf { it.amount },
                    otpCount = otpCount,
                    unreadImportant = unreadImportant,
                ),
            )
            return Result.success()
        }

        companion object {
            const val WORK_NAME = "daily_summary"

            /** Whether a digest is due for [frequency] on [dayOfWeek]. */
            fun shouldRun(
                frequency: SummaryFrequency,
                dayOfWeek: DayOfWeek,
            ): Boolean =
                when (frequency) {
                    SummaryFrequency.OFF -> false
                    SummaryFrequency.DAILY -> true
                    SummaryFrequency.WEEKLY -> dayOfWeek == DayOfWeek.MONDAY
                }
        }
    }
